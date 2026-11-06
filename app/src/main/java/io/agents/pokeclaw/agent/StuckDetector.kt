// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.utils.XLog
import java.util.ArrayDeque
import java.security.MessageDigest
import com.google.gson.Gson

/**
 * Detects unchanged-screen/error stalls and repeated screen-content navigation cycles.
 *
 * Architecture reference:
 * - Sliding window (8 steps), same-action, screen-unchanged, high-repetition
 * - ralph-claude-code: repeated-error detection
 * - PokeClaw original: screen diff (previousScreenTexts)
 *
 * Recovery is 3-level:
 *   Level 1: Inject recovery hint into prompt
 *   Level 2: Suggest strategy switch (different tool)
 *   Level 3: Auto-kill (force finish)
 */
class StuckDetector(private val windowSize: Int = 8) {

    private val actions = ArrayDeque<String>(windowSize + 1)
    private val screenHashes = ArrayDeque<Int>(windowSize + 1)
    private val screenDiffCounts = ArrayDeque<Int>(windowSize + 1)
    private val errors = ArrayDeque<String>(windowSize + 1)
    private var consecutiveStuckSteps = 0
    private val screenTransitions = ArrayDeque<ScreenSnapshot>(SCREEN_CYCLE_WINDOW)
    private var consecutiveCycleDetections = 0

    private data class ScreenNode(val textHash: String?, val textLength: Int, val layout: String)
    private data class ScreenFeatures(val nodes: List<ScreenNode>, val controls: List<String>)
    private data class ScreenSnapshot(val fingerprint: String, val features: ScreenFeatures?)

    sealed class Signal(val description: String) {
        class ScreenCycle(val period: Int) :
            Signal("A matching or highly similar $period-screen content sequence repeated $SCREEN_CYCLE_REPEATS times")

        class SameAction(val action: String, val count: Int) :
            Signal("Same action '$action' repeated $count times consecutively")

        class ScreenUnchanged(val steps: Int) :
            Signal("Screen unchanged for $steps consecutive steps")

        class ZeroDiff(val steps: Int) :
            Signal("Zero screen text diff for $steps consecutive steps")

        class HighRepetition(val action: String, val count: Int, val window: Int) :
            Signal("Action '$action' appeared $count times in last $window steps")

        class RepeatedError(val error: String, val count: Int) :
            Signal("Same error repeated $count times consecutively")
    }

    enum class RecoveryLevel {
        HINT,           // Level 1: inject recovery hint
        STRATEGY_SWITCH, // Level 2: suggest different approach
        AUTO_KILL        // Level 3: force finish
    }

    data class Detection(
        val signal: Signal,
        val level: RecoveryLevel,
        val recoveryHint: String
    )

    /**
     * Record one agent loop step and check for stuck patterns.
     *
     * @param action tool name + full canonical arguments digest (no user text)
     * @param screenHash fresh screen hash, null when observation is unavailable
     * @param screenDiffCount actual changed-line count, null without a comparable baseline
     * @param error actual tool error; only its digest is retained/logged
     * @param observationOnly successful reads do not count as failed action attempts
     * @param screenContent existing fresh tree; never request another scan for cycle detection
     * @param screenObservationExpected a missing expected tree breaks cycle continuity
     * @return Detection if stuck, null if OK
     */
    fun record(
        action: String,
        screenHash: Int?,
        screenDiffCount: Int?,
        error: String?,
        observationOnly: Boolean = false,
        screenContent: String? = null,
        screenObservationExpected: Boolean = false
    ): Detection? {
        val cycle = if (screenObservationExpected) recordScreenContent(screenContent) else null
        if (error != null) {
            errors.addLast(digest(error))
            if (errors.size > windowSize) errors.removeFirst()
        } else if (!observationOnly) {
            errors.clear()
        }

        if (observationOnly && error == null) {
            if (screenHash != null && screenDiffCount != null && screenDiffCount > 0) {
                actions.clear()
                screenHashes.clear()
                screenDiffCounts.clear()
                if (errors.isEmpty()) consecutiveStuckSteps = 0
            }
            return cycle
        }

        // Only the unchanged-screen detector resets here; navigation cycles retain their history.
        if (screenHash == null || screenDiffCount == null || screenDiffCount > 0) {
            actions.clear()
            screenHashes.clear()
            screenDiffCounts.clear()
            if (error == null) consecutiveStuckSteps = 0
        }
        val hasStableObservation = !observationOnly && screenHash != null && screenDiffCount == 0
        if (hasStableObservation) {
            actions.addLast(action)
            if (actions.size > windowSize) actions.removeFirst()
            screenHashes.addLast(screenHash!!)
            if (screenHashes.size > windowSize) screenHashes.removeFirst()
            screenDiffCounts.addLast(screenDiffCount!!)
            if (screenDiffCounts.size > windowSize) screenDiffCounts.removeFirst()
        }

        val signal = checkRepeatedError() ?: if (hasStableObservation) {
            checkSameAction() ?: checkScreenUnchanged() ?: checkZeroDiff() ?: checkHighRepetition()
        } else null

        if (signal != null) {
            consecutiveStuckSteps++
            val level = when {
                consecutiveStuckSteps >= 5 -> RecoveryLevel.AUTO_KILL
                consecutiveStuckSteps >= 3 -> RecoveryLevel.STRATEGY_SWITCH
                else -> RecoveryLevel.HINT
            }
            val hint = generateRecoveryHint(signal, level)
            val detection = Detection(signal, level, hint)
            XLog.w(TAG, "[StuckDetector] ${signal.description} → Level ${level.name}")
            return if (cycle != null && cycle.level.ordinal >= detection.level.ordinal) cycle else detection
        }

        // No unchanged-screen/error signal; the independent cycle may still need recovery.
        consecutiveStuckSteps = 0
        return cycle
    }

    private fun recordScreenContent(content: String?): Detection? {
        val started = System.nanoTime()
        var outcome = "unknown"
        try {
            if (content.isNullOrBlank()) {
                screenTransitions.clear()
                consecutiveCycleDetections = 0
                return null
            }
            val fingerprint = digest(NODE_LABEL.replace(content, "$1[node]").trim())
            val previous = screenTransitions.peekLast()
            if (previous?.fingerprint == fingerprint) {
                outcome = "same-exact-content"
                return null
            }
            val snapshot = ScreenSnapshot(fingerprint, screenFeatures(content))
            if (previous != null && similarScreens(previous, snapshot, "consecutive")) {
                outcome = "same-content"
                return null
            }
            screenTransitions.addLast(snapshot)
            if (screenTransitions.size > SCREEN_CYCLE_WINDOW) screenTransitions.removeFirst()
            val history = screenTransitions.toList()
            val period = (2..MAX_SCREEN_CYCLE_PERIOD).firstOrNull { candidate ->
                val length = candidate * SCREEN_CYCLE_REPEATS
                if (history.size < length) false else {
                    val start = history.size - length
                    // Pair every revisit, not just neighbours: similarity is not transitive.
                    (0 until candidate).all { phase ->
                        (0 until SCREEN_CYCLE_REPEATS - 1).all { first ->
                            (first + 1 until SCREEN_CYCLE_REPEATS).all { second ->
                                similarScreens(
                                    history[start + phase + first * candidate],
                                    history[start + phase + second * candidate],
                                    "period=$candidate phase=$phase revisit=$first/$second"
                                )
                            }
                        }
                    }
                }
            }
            if (period == null) {
                outcome = "new-sequence"
                consecutiveCycleDetections = 0
                return null
            }
            consecutiveCycleDetections++
            val level = when {
                consecutiveCycleDetections >= 5 -> RecoveryLevel.AUTO_KILL
                consecutiveCycleDetections >= 3 -> RecoveryLevel.STRATEGY_SWITCH
                else -> RecoveryLevel.HINT
            }
            val signal = Signal.ScreenCycle(period)
            outcome = "cycle-$period/${level.name}"
            XLog.w(TAG, "[ScreenCycle] ${signal.description}; detection=$consecutiveCycleDetections → ${level.name}")
            return Detection(signal, level, generateRecoveryHint(signal, level))
        } finally {
            XLog.d(TAG, "[ScreenCycle] outcome=$outcome transitions=${screenTransitions.size} recovery=$consecutiveCycleDetections elapsedUs=${(System.nanoTime() - started) / 1000}")
        }
    }

    private fun screenFeatures(content: String): ScreenFeatures? {
        fun exactOnly(reason: String): ScreenFeatures? {
            XLog.d(TAG, "[ScreenSimilarity] features=exact-only reason=$reason")
            return null
        }
        if (content.length > MAX_SIMILARITY_CHARS) return exactOnly("size-limit")
        val labels = NODE_LABEL.findAll(content).take(MAX_SIMILARITY_NODES + 1).toList()
        if (labels.isEmpty() || labels.size > MAX_SIMILARITY_NODES) return exactOnly("node-count")
        if (content.substring(0, labels.first().range.first).isNotBlank()) return exactOnly("noncompact-prefix")
        val nodes = ArrayList<ScreenNode>(labels.size)
        val controls = ArrayList<String>()
        labels.forEachIndexed { index, label ->
            val end = labels.getOrNull(index + 1)?.range?.first ?: content.length
            val body = content.substring(label.range.last + 1, end).removePrefix(" ").trimEnd()
            val parsed = NODE_BODY.matchEntire(body) ?: return exactOnly("noncompact-node")
            val text = parsed.groupValues[1]
            val textHash = if (text.isBlank()) null else digest(text)
            val flags = parsed.groupValues[2]
            val depth = label.groupValues[1].length
            nodes.add(ScreenNode(textHash, if (textHash == null) 0 else text.length,
                "$depth|$flags|${parsed.groupValues[3]},${parsed.groupValues[4]}"))
            // A changed control label, editable value or on/off state is never diluted by page size.
            if (flags.isNotEmpty()) controls.add("$depth|$flags|${textHash.orEmpty()}")
        }
        XLog.d(TAG, "[ScreenSimilarity] features=compact nodes=${nodes.size} controls=${controls.size}")
        return ScreenFeatures(nodes, controls)
    }

    private fun similarScreens(a: ScreenSnapshot, b: ScreenSnapshot, context: String): Boolean {
        if (a.fingerprint == b.fingerprint) {
            XLog.d(TAG, "[ScreenSimilarity] $context match=true reason=exact score=1.0")
            return true
        }
        val left = a.features
        val right = b.features
        if (left == null || right == null) {
            XLog.d(TAG, "[ScreenSimilarity] $context match=false reason=exact-only")
            return false
        }
        val leftTexts = left.nodes.mapNotNull { it.textHash }
        val rightTexts = right.nodes.mapNotNull { it.textHash }
        if (minOf(leftTexts.size, rightTexts.size) < MIN_SIMILARITY_TEXT_NODES) {
            XLog.d(TAG, "[ScreenSimilarity] $context match=false reason=sparse-text")
            return false
        }
        var matchedTexts = 0
        var matchedCharacters = 0
        var matchedLayout = 0
        for (index in 0 until minOf(left.nodes.size, right.nodes.size)) {
            val x = left.nodes[index]
            val y = right.nodes[index]
            if (x.textHash != null && x.textHash == y.textHash) {
                matchedTexts++
                matchedCharacters += x.textLength + y.textLength
            }
            if (x.layout == y.layout) matchedLayout++
        }
        // Count agreement prevents long boilerplate dominating; character agreement protects long content.
        val textScore = 2.0 * matchedTexts / (leftTexts.size + rightTexts.size)
        val characterScore = matchedCharacters.toDouble() /
            (left.nodes.sumOf { it.textLength } + right.nodes.sumOf { it.textLength })
        val layoutScore = 2.0 * matchedLayout / (left.nodes.size + right.nodes.size)
        val score = 0.55 * textScore + 0.30 * characterScore + 0.15 * layoutScore
        val reason = when {
            left.controls != right.controls -> "control-change"
            leftTexts != rightTexts && leftTexts.groupingBy { it }.eachCount() ==
                rightTexts.groupingBy { it }.eachCount() -> "text-reordered"
            textScore < MIN_TEXT_SIMILARITY -> "text-change"
            characterScore < MIN_TEXT_SIMILARITY -> "content-length-change"
            layoutScore < MIN_LAYOUT_SIMILARITY -> "layout-change"
            score < MIN_SCREEN_SIMILARITY -> "below-threshold"
            else -> "similar"
        }
        val matches = reason == "similar"
        XLog.d(TAG, "[ScreenSimilarity] $context match=$matches reason=$reason score=$score " +
            "text=$textScore characters=$characterScore layout=$layoutScore threshold=$MIN_SCREEN_SIMILARITY")
        return matches
    }

    private fun checkSameAction(): Signal? {
        if (actions.size < 3) return null
        val last3 = actions.toList().takeLast(3)
        return if (last3.all { it == last3[0] }) {
            Signal.SameAction(last3[0].take(50), 3)
        } else null
    }

    private fun checkScreenUnchanged(): Signal? {
        if (screenHashes.size < 3) return null
        val last3 = screenHashes.toList().takeLast(3)
        return if (last3.all { it == last3[0] }) {
            Signal.ScreenUnchanged(3)
        } else null
    }

    private fun checkZeroDiff(): Signal? {
        if (screenDiffCounts.size < 3) return null
        val last3 = screenDiffCounts.toList().takeLast(3)
        return if (last3.all { it == 0 }) {
            Signal.ZeroDiff(3)
        } else null
    }

    private fun checkHighRepetition(): Signal? {
        if (actions.size < windowSize) return null
        val counts = actions.groupingBy { it }.eachCount()
        val maxEntry = counts.maxByOrNull { it.value } ?: return null
        return if (maxEntry.value >= 3) {
            Signal.HighRepetition(maxEntry.key.take(50), maxEntry.value, windowSize)
        } else null
    }

    private fun checkRepeatedError(): Signal? {
        if (errors.size < 3) return null
        val last3 = errors.toList().takeLast(3)
        return if (last3.all { it == last3[0] }) {
            Signal.RepeatedError(last3[0].take(80), 3)
        } else null
    }

    private fun generateRecoveryHint(signal: Signal, level: RecoveryLevel): String {
        val base = when (signal) {
            is Signal.ScreenCycle ->
                "The last ${signal.period * SCREEN_CYCLE_REPEATS} screen transitions repeat a matching or highly similar ${signal.period}-screen content sequence. Small text differences may remain; similarity is not proof that no meaningful progress occurred. Screen changes alone are not progress. Review the actual differences, observations and your prior confirmations already in this task's history before repeating this navigation. If there is no verified progress, choose an unvisited part of the task or a different approach. Only finish successfully if the observed evidence supports completion; otherwise explain the limitation. Do not replay completed external actions."
            is Signal.SameAction -> when {
                signal.action.contains("find_and_tap") ->
                    "Your find_and_tap action is not working. Try using tap_node with a specific node ID from get_screen_info, or use system_key(key=\"enter\") to submit."
                signal.action.contains("scroll") ->
                    "You may have reached the end of scrollable content. Try a different approach or press back."
                signal.action.contains("tap") ->
                    "Your tap action may not be hitting the right target. Call get_screen_info to refresh the screen state and try a different element."
                else ->
                    "Your last action '${signal.action}' is not producing results. Try a completely different approach."
            }
            is Signal.ScreenUnchanged ->
                "The screen has not changed for ${signal.steps} steps. Your actions may not be having any effect. Try pressing system_key(key=\"back\") or system_key(key=\"home\") and restart from a different angle."
            is Signal.ZeroDiff ->
                "No new content has appeared on screen. You may be stuck. Try navigating away and back, or use a different tool."
            is Signal.HighRepetition ->
                "You are repeating '${signal.action}' too frequently. This approach is not working. Try something fundamentally different."
            is Signal.RepeatedError ->
                "The same tool error keeps occurring. Review the failed tool results and latest screen; do not retry the same approach. Try a different valid tool or strategy."
        }

        return when (level) {
            RecoveryLevel.HINT ->
                "[System Notice] $base"
            RecoveryLevel.STRATEGY_SWITCH ->
                "[System Warning] You have been stuck for multiple rounds. $base If you cannot make progress, call finish and explain what went wrong."
            RecoveryLevel.AUTO_KILL ->
                "" // caller handles auto-kill
        }
    }

    fun reset() {
        actions.clear()
        screenHashes.clear()
        screenDiffCounts.clear()
        errors.clear()
        consecutiveStuckSteps = 0
        screenTransitions.clear()
        consecutiveCycleDetections = 0
    }

    companion object {
        private const val TAG = "StuckDetector"
        private const val MAX_SCREEN_CYCLE_PERIOD = 4
        private const val SCREEN_CYCLE_REPEATS = 3
        private const val SCREEN_CYCLE_WINDOW = MAX_SCREEN_CYCLE_PERIOD * SCREEN_CYCLE_REPEATS
        private val NODE_LABEL = Regex("(?m)^([ \\t]*)\\[n\\d+\\]")
        private val NODE_BODY = Regex(
            """^(?:"(.*)")?((?: (?:tap|edit|scroll|on|off))*) \((-?\d+),(-?\d+)\)$""",
            RegexOption.DOT_MATCHES_ALL
        )
        // Conservative starting thresholds; real-device SS1–SS4 gate calibration and acceptance.
        private const val MIN_SCREEN_SIMILARITY = 0.96
        private const val MIN_TEXT_SIMILARITY = 0.90
        private const val MIN_LAYOUT_SIMILARITY = 0.95
        private const val MIN_SIMILARITY_TEXT_NODES = 12
        private const val MAX_SIMILARITY_NODES = 512
        private const val MAX_SIMILARITY_CHARS = 250_000
        private val gson = Gson()

        fun actionFingerprint(toolName: String, arguments: Map<String, Any>): String =
            "$toolName:${digest(gson.toJson(canonicalize(arguments)))}"

        private fun canonicalize(value: Any?): Any? = when (value) {
            is Map<*, *> -> value.entries.sortedBy { it.key.toString() }
                .associate { it.key.toString() to canonicalize(it.value) }
            is List<*> -> value.map { canonicalize(it) }
            else -> value
        }

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
