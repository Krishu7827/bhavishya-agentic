// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.agents.pokeclaw.utils.XLog
import java.security.MessageDigest

class PermissionDialogUnavailable(val reason: String = "screen_unavailable") :
    RuntimeException("Exposed screen/action unavailable ($reason). Read a fresh screen; if Android hides or rejects the control, explain the limitation and ask for manual help. Do not guess coordinates or assume permission was granted.")

data class PermissionOption(
    val path: List<Int>, val label: String,
    val enabled: Boolean, val checkable: Boolean, val checked: Boolean, val selected: Boolean,
)
data class PermissionDialogSnapshot(
    val fingerprint: String,
    val message: String,
    val options: List<PermissionOption>,
)

/** Reads live system-owned UI, never model-provided labels or cached node coordinates. */
object PermissionDialog {
    private const val TAG = "PermissionDialog"
    private var observedSnapshot: PermissionDialogSnapshot? = null
    private val controllerPackages = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    @JvmStatic
    fun isController(service: ClawAccessibilityService, pkg: String): Boolean =
        controllerPackages.getOrPut(pkg) { resolveController(service, pkg) }

    private fun resolveController(service: ClawAccessibilityService, pkg: String): Boolean {
        val pm = service.packageManager
        val controller = pm.resolveActivity(Intent("android.intent.action.MANAGE_PERMISSIONS"), 0)
            ?.activityInfo?.packageName
        if (pkg != controller && pkg !in setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller",
                "com.android.packageinstaller", "com.google.android.packageinstaller")) return false
        return try {
            pm.getApplicationInfo(pkg, 0).flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (_: Exception) { false }
    }

    @JvmStatic
    fun foregroundRoot(service: ClawAccessibilityService): AccessibilityNodeInfo {
        val windows = service.windows
        try {
            // Never fall through to an underlying app when the selected foreground is unreadable.
            val foreground = windows.sortedByDescending { it.layer }.firstOrNull {
                it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    (it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.isActive || it.isFocused)
            } ?: throw PermissionDialogUnavailable("interactive_window_unavailable")
            val activeRoot = service.rootInActiveWindow
            XLog.d(TAG, "Read active root: foreground=${foreground.id}, rootWindow=${activeRoot?.windowId}, visible=${activeRoot?.isVisibleToUser}")
            if (activeRoot != null) {
                if (activeRoot.windowId == foreground.id && activeRoot.isVisibleToUser) return activeRoot
                activeRoot.recycle()
                XLog.w(TAG, "Active root not verified against foreground; trying only selected window")
            }
            val root = foreground.root
            XLog.d(TAG, "Read window fallback: id=${foreground.id}, type=${foreground.type}, layer=${foreground.layer}, active=${foreground.isActive}, focused=${foreground.isFocused}, root=${root != null}")
            if (root == null) throw PermissionDialogUnavailable("foreground_root_unavailable")
            if (root.windowId != foreground.id || !root.isVisibleToUser) {
                root.recycle()
                XLog.w(TAG, "Window fallback rejected: foreground_not_verified")
                throw PermissionDialogUnavailable("foreground_not_verified")
            }
            return root
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    @JvmStatic
    @Synchronized
    fun describe(service: ClawAccessibilityService, root: AccessibilityNodeInfo): String? {
        observedSnapshot = null
        val screen = snapshot(service, root)
        observedSnapshot = screen
        io.agents.pokeclaw.agent.PermissionConsentSession.recordObservation(screen != null)
        if (screen == null) return null
        XLog.i(TAG, "Exposed permission screen: package=${root.packageName}, options=${screen.options.size}")
        return buildString {
            appendLine("Permission/consent screen: ${root.packageName}")
            appendLine(screen.message)
            screen.options.forEachIndexed { i, option ->
                append("[p${i + 1}] ${option.label} ${if (option.enabled) "enabled" else "disabled"}")
                if (option.checkable) append(if (option.checked) " checked" else " unchecked")
                if (option.selected) append(" selected")
                appendLine()
            }
            append("Use these fresh option IDs with tap_node only for the user's clear choice. A toggle is not a grant; verify the resulting screen.")
        }
    }

    @JvmStatic
    @Synchronized
    fun clearObservation() {
        observedSnapshot = null
    }

    @JvmStatic
    @Synchronized
    fun invalidateObservation() {
        observedSnapshot = null
        io.agents.pokeclaw.agent.PermissionConsentSession.recordObservation(true)
    }

    @JvmStatic
    @Synchronized
    fun clickOption(service: ClawAccessibilityService, nodeId: String): Boolean {
        XLog.i(TAG, "clickOption: nodeId=$nodeId, hasObservation=${observedSnapshot != null}")
        val expected = observedSnapshot ?: throw PermissionDialogUnavailable("fresh_permission_observation_required")
        val index = nodeId.removePrefix("p").toIntOrNull()?.minus(1)
            ?: throw PermissionDialogUnavailable("invalid_permission_option")
        return try {
            clickSelected(service, expected, index) { click ->
                io.agents.pokeclaw.agent.PermissionConsentSession.dispatchPermissionAction(click)
            }
        } finally {
            observedSnapshot = null
            io.agents.pokeclaw.agent.PermissionConsentSession.invalidatePermissionTargets()
        }
    }

    private fun snapshot(service: ClawAccessibilityService, root: AccessibilityNodeInfo): PermissionDialogSnapshot? {
        val pkg = root.packageName?.toString() ?: throw PermissionDialogUnavailable()
        if (!isController(service, pkg)) return null
        val records = StringBuilder("$pkg|${root.windowId}|")
        val text = linkedSetOf<String>()
        val options = mutableListOf<PermissionOption>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, path: List<Int>): List<String> {
            io.agents.pokeclaw.agent.PermissionConsentSession.checkCancellation()
            if (++visited > 400 || path.size > 30 || records.length > 32000) {
                throw PermissionDialogUnavailable("permission_tree_limit")
            }
            val labels = mutableListOf<String>()
            val label = node.text?.toString()?.trim().orEmpty()
                .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
            if (node.isVisibleToUser) {
                val id = node.viewIdResourceName.orEmpty()
                val bounds = Rect().also(node::getBoundsInScreen)
                records.append(listOf(path, id, label, bounds, node.isEnabled, node.isClickable,
                    node.isCheckable, node.isChecked, node.isSelected).joinToString("|")).append('\n')
                if (label.isNotBlank()) {
                    text.add(label)
                    labels.add(label)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: throw PermissionDialogUnavailable("incomplete_permission_tree")
                try { labels.addAll(walk(child, path + i)) } finally { child.recycle() }
            }
            // Some OEM choices expose their label on a child of the clickable container.
            val optionLabel = label.ifBlank { labels.distinct().joinToString(" / ") }
            if (node.isVisibleToUser && node.isClickable && optionLabel.isNotBlank() &&
                node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                options.add(PermissionOption(path, optionLabel, node.isEnabled, node.isCheckable,
                    node.isChecked, node.isSelected))
            }
            return labels
        }
        walk(root, emptyList())
        if (options.size > 32 || records.length > 32000) {
            throw PermissionDialogUnavailable("permission_tree_limit")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(records.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        return PermissionDialogSnapshot(digest, text.joinToString("\n"), options)
    }

    @JvmStatic
    fun requireGestureAllowed(service: ClawAccessibilityService) {
        io.agents.pokeclaw.agent.PermissionConsentSession.checkCancellation()
        val root = foregroundRoot(service)
        try {
            if (isController(service, root.packageName?.toString().orEmpty())) {
                XLog.w(TAG, "Permission gesture not dispatched; use an exposed option's ACTION_CLICK")
                throw PermissionDialogUnavailable("permission_requires_observed_node_action")
            }
        } finally { root.recycle() }
    }

    @JvmStatic
    fun requiresNodeAction(service: ClawAccessibilityService, node: AccessibilityNodeInfo): Boolean =
        isController(service, node.packageName?.toString().orEmpty()) ||
            (android.os.Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)

    @JvmStatic
    @Synchronized
    fun validateNodeAction(service: ClawAccessibilityService, node: AccessibilityNodeInfo) {
        if (!isController(service, node.packageName?.toString().orEmpty())) return
        val root = foregroundRoot(service)
        try {
            val expected = observedSnapshot
            if (expected == null || snapshot(service, root) != expected ||
                !node.refresh() || node.windowId != root.windowId || node.packageName != root.packageName ||
                !node.isVisibleToUser || !node.isEnabled) {
                throw PermissionDialogUnavailable("permission_changed_or_unobserved")
            }
        } finally { root.recycle() }
    }

    private fun clickSelected(
        service: ClawAccessibilityService,
        expected: PermissionDialogSnapshot,
        index: Int,
        dispatch: (() -> Boolean) -> Boolean,
    ): Boolean {
        val root = foregroundRoot(service)
        try {
            val current = snapshot(service, root)
            if (current != expected) {
                XLog.i(TAG, "Dialog changed before selected action; invalidating choice")
                return false
            }
            fun reject(reason: String): Boolean {
                XLog.w(TAG, "Permission choice rejected: option=${index + 1}, reason=$reason")
                return false
            }
            val option = current.options.getOrNull(index) ?: return reject("option_not_found")
            var node = AccessibilityNodeInfo.obtain(root)
            try {
                for (childIndex in option.path) {
                    val child = node.getChild(childIndex) ?: return reject("node_path_changed")
                    node.recycle()
                    node = child
                }
                if (!node.refresh() || !node.isVisibleToUser || !node.isEnabled || !node.isClickable) {
                    return reject("node_unavailable_or_disabled")
                }
                val label = node.text?.toString()?.trim().orEmpty()
                    .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
                if ((label.isNotBlank() && label != option.label) ||
                    node.windowId != root.windowId || node.packageName != root.packageName ||
                    node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
                    return reject("node_identity_or_action_changed")
                }
                XLog.d(TAG, "Dispatching permission ACTION_CLICK: option=${index + 1}, window=${node.windowId}, id=${node.viewIdResourceName}")
                val clicked = dispatch { node.performAction(AccessibilityNodeInfo.ACTION_CLICK, Bundle.EMPTY) }
                XLog.i(TAG, "Exposed option ACTION_CLICK: option=${index + 1}, dispatched=$clicked")
                if (!clicked) throw PermissionDialogUnavailable("permission_node_action_rejected")
                return true
            } finally { node.recycle() }
        } finally { root.recycle() }
    }
}
