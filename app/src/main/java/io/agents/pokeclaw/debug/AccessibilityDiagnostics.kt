package io.agents.pokeclaw.debug

import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import io.agents.pokeclaw.BuildConfig
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.PermissionDialog
import io.agents.pokeclaw.service.PermissionDialogUnavailable
import io.agents.pokeclaw.utils.ContactListUiUtils
import io.agents.pokeclaw.utils.ContactMatchUtils
import io.agents.pokeclaw.utils.XLog

/** Explicit debug broadcasts only; does not populate or invalidate actionable snapshots. */
object AccessibilityDiagnostics {
    private const val TAG = "A11yDiagnostics"
    private const val MAX_NODES = 80
    private const val MAX_DEPTH = 12

    fun inspect(service: ClawAccessibilityService) {
        check(BuildConfig.DEBUG) { "Accessibility diagnostics require a debug build" }
        val start = SystemClock.elapsedRealtime()
        val info = service.serviceInfo
        val accessibilityTool = if (Build.VERSION.SDK_INT >= 31) info?.isAccessibilityTool else null
        XLog.i(TAG, "inspect begin: sdk=${Build.VERSION.SDK_INT}, isAccessibilityTool=$accessibilityTool, flags=${info?.flags}, capabilities=${info?.capabilities}")
        inspectRoot("rootInActiveWindow", service.rootInActiveWindow)
        val windows = service.windows
        try {
            XLog.i(TAG, "windows=${windows.size}; separate API reads may span a window transition")
            windows.sortedByDescending { it.layer }.take(32).forEach { window ->
                XLog.i(TAG, "window: id=${window.id}, type=${window.type}, layer=${window.layer}, active=${window.isActive}, focused=${window.isFocused}")
                if ((window.isActive || window.isFocused) &&
                    window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    inspectRoot("window.getRoot(${window.id})", window.root)
                }
            }
            if (windows.size > 32) XLog.w(TAG, "Window metadata truncated at 32 entries")
        } finally {
            windows.forEach { it.recycle() }
            XLog.i(TAG, "inspect end: elapsedMs=${SystemClock.elapsedRealtime() - start}; no actions dispatched")
        }
    }

    private data class NavigationObservation(
        val window: Int,
        val packageName: String,
        val editables: Int,
        val toolbarMatches: Int,
        val lookupReady: Boolean?,
        val complete: Boolean,
    )

    @Synchronized
    fun compareNavigation(service: ClawAccessibilityService, targetPackage: String, contact: String) {
        check(BuildConfig.DEBUG) { "Accessibility diagnostics require a debug build" }
        require(targetPackage.isNotBlank() && contact.isNotBlank() && contact.length <= 200) {
            "Navigation comparison requires target_package and target_contact (1–200 characters)"
        }
        XLog.i(TAG, "compare-navigation begin: targetPackage=$targetPackage; contact text omitted; read-only")
        val before = inspectNavigationRoot("direct-before", service.rootInActiveWindow, targetPackage, contact)
        val guarded = try {
            inspectNavigationRoot("guarded", PermissionDialog.foregroundRoot(service), targetPackage, contact)
        } catch (e: PermissionDialogUnavailable) {
            XLog.w(TAG, "compare-navigation guarded unavailable: reason=${e.reason}")
            null
        }
        val after = inspectNavigationRoot("direct-after", service.rootInActiveWindow, targetPackage, contact)
        val equivalent = before?.complete == true && before == guarded && before == after
        XLog.i(TAG, "compare-navigation end: equivalentCompleteObservations=$equivalent; sequential reads, not atomic; no actions dispatched")
    }

    @Synchronized
    fun navigateContact(service: ClawAccessibilityService, targetPackage: String, contact: String, prepareOnly: Boolean): Boolean {
        check(BuildConfig.DEBUG) { "Accessibility diagnostics require a debug build" }
        require(targetPackage.isNotBlank() && contact.isNotBlank() && contact.length <= 200) {
            "Navigation requires target_package and target_contact (1–200 characters)"
        }
        val taskRunning = io.agents.pokeclaw.ClawApplication.appViewModelInstance.isTaskRunning()
        val monitorEnabled = io.agents.pokeclaw.service.AutoReplyManager.getInstance().isEnabled
        XLog.i(TAG, "navigation idle check: taskRunning=$taskRunning, monitorEnabled=$monitorEnabled")
        check(!taskRunning && !monitorEnabled) { "Stop tasks and monitoring before navigation QA" }
        XLog.i(TAG, "navigation begin: targetPackage=$targetPackage, prepareOnly=$prepareOnly; no message/model calls")
        val result = if (prepareOnly) ContactListUiUtils.prepareForContactLookup(service, targetPackage, 4, 800)
        else io.agents.pokeclaw.service.AutoReplyManager.getInstance().navigateToContactChat(service, targetPackage, contact)
        if (result && !prepareOnly) Thread.sleep(1200)
        inspectNavigationRoot("navigation-result", service.rootInActiveWindow, targetPackage, contact)
        XLog.i(TAG, "navigation end: targetPackage=$targetPackage, success=$result, prepareOnly=$prepareOnly; production navigation, no message/model calls")
        return result
    }

    private fun inspectNavigationRoot(
        source: String,
        root: AccessibilityNodeInfo?,
        targetPackage: String,
        contact: String,
    ): NavigationObservation? {
        if (root == null) {
            XLog.w(TAG, "compare-navigation $source: root=null")
            return null
        }
        val aliases = ContactMatchUtils.buildNormalizedAliases(contact)
        val digits = ContactMatchUtils.buildDigitAliases(contact)
        var visited = 0
        var missing = 0
        var truncated = false
        var editables = 0
        var toolbarMatches = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= 2048 || depth > 64) {
                truncated = true
                return
            }
            visited++
            val bounds = Rect().also(node::getBoundsInScreen)
            // Raw legacy candidates for comparison only; actual production identity is logged separately.
            val editable = node.isEditable || node.className?.toString()?.contains("EditText") == true
            val toolbarMatch = bounds.top < 300 &&
                ContactMatchUtils.matchesTarget(node.text, node.contentDescription, aliases, digits)
            if (editable) editables++
            if (toolbarMatch) toolbarMatches++
            if ((editable && editables <= 8) || (toolbarMatch && toolbarMatches <= 8)) {
                XLog.i(TAG, "compare-navigation $source match: depth=$depth, id=${node.viewIdResourceName}, class=${node.className}, bounds=$bounds, visible=${node.isVisibleToUser}, editable=$editable, toolbarMatch=$toolbarMatch; text omitted")
            }
            for (i in 0 until node.childCount) {
                if (visited >= 2048 || depth >= 64) {
                    truncated = true
                    break
                }
                val child = node.getChild(i)
                if (child == null) {
                    missing++
                    continue
                }
                try { walk(child, depth + 1) } finally { child.recycle() }
            }
        }
        try {
            walk(root, 0)
            val complete = !truncated && missing == 0
            val verifiedChat = if (complete) ContactListUiUtils.isTargetChat(root, targetPackage, contact) else null
            XLog.i(TAG, "compare-navigation $source: productionTargetChat=$verifiedChat")
            val ready = if (complete) ContactListUiUtils.isContactLookupReady(root) else null
            val targetReady = if (complete) root.packageName?.toString() == targetPackage && ready == true else null
            val observation = NavigationObservation(root.windowId, root.packageName?.toString().orEmpty(),
                editables, toolbarMatches, ready, complete)
            XLog.i(TAG, "compare-navigation $source: window=${observation.window}, package=${observation.packageName}, targetPackage=${observation.packageName == targetPackage}, nodes=$visited, editables=$editables, toolbarMatches=$toolbarMatches, lookupReady=$ready, targetLookupReady=$targetReady, complete=$complete, missingChildren=$missing, truncated=$truncated")
            return observation
        } finally { root.recycle() }
    }

    private fun inspectRoot(source: String, root: AccessibilityNodeInfo?) {
        if (root == null) {
            XLog.w(TAG, "$source: root=null")
            return
        }
        var visited = 0
        var truncated = false
        var missingChildren = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= MAX_NODES || depth > MAX_DEPTH) {
                truncated = true
                return
            }
            visited++
            val sensitive = if (Build.VERSION.SDK_INT >= 34) node.isAccessibilityDataSensitive else null
            XLog.i(TAG, "$source node#$visited: depth=$depth, window=${node.windowId}, package=${node.packageName}, id=${node.viewIdResourceName}, class=${node.className}, visible=${node.isVisibleToUser}, enabled=${node.isEnabled}, clickable=${node.isClickable}, checkable=${node.isCheckable}, checked=${node.isChecked}, selected=${node.isSelected}, sensitive=$sensitive, actions=${node.actionList.map { it.id }}, children=${node.childCount}")
            for (i in 0 until node.childCount) {
                if (visited >= MAX_NODES || depth >= MAX_DEPTH) {
                    truncated = true
                    break
                }
                val child = node.getChild(i)
                if (child == null) {
                    missingChildren++
                    continue
                }
                try { walk(child, depth + 1) } finally { child.recycle() }
            }
        }
        try {
            walk(root, 0)
            XLog.i(TAG, "$source summary: nodes=$visited, missingChildren=$missingChildren, truncated=$truncated; text and descriptions omitted")
        } finally {
            root.recycle()
        }
    }
}
