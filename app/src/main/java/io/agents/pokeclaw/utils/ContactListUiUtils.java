// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils;

import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.agents.pokeclaw.service.ClawAccessibilityService;

/**
 * Generic contact/chat-list search helpers.
 *
 * The goal is to avoid app-specific assumptions:
 * - search current screen first
 * - prefer text matches over contentDescription-only matches
 * - scroll multiple times if needed
 * - stop once the screen stops changing
 */
public final class ContactListUiUtils {
    private static final String TAG = "ContactListUiUtils";
    private static final String[] CLOSE_HINTS = {
        "close", "dismiss", "cancel", "got it", "ok", "done",
        "關閉", "关闭", "取消", "知道了", "確定", "确定",
        "cerrar", "fermer", "schließen", "닫기", "閉じる", "закрыть", "fechar"
    };

    private enum SearchAttemptResult {
        FOUND,
        NO_MATCH,
        SEARCH_UI_MISSING,
        NO_SEARCH,
        TYPE_FAILED
    }

    private ContactListUiUtils() {}

    public static boolean prepareForContactLookup(
        ClawAccessibilityService service,
        String packageName,
        int maxBacks,
        long settleMs
    ) throws InterruptedException {
        return prepareForContactLookup(service, packageName, maxBacks, settleMs, null);
    }

    public static boolean prepareForContactLookup(ClawAccessibilityService service, String packageName,
        int maxBacks, long settleMs, String contact) throws InterruptedException {
        int attempts = Math.min(Math.max(maxBacks, 1), 6);
        for (int attempt = 0; attempt <= attempts; attempt++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (!isTargetPackage(root, packageName)) {
                XLog.i(TAG, "prepareForContactLookup: reopening target=" + packageName);
                if (!service.openApp(packageName)) return false;
                Thread.sleep(Math.max(settleMs, 700L));
                continue;
            }
            if (dismissBlockingOverlay(service, root, settleMs)) {
                continue;
            }
            if ((contact != null && isTargetChat(root, packageName, contact)) || isContactLookupReady(root)) {
                XLog.i(TAG, "prepareForContactLookup: ready on attempt=" + attempt);
                return true;
            }

            if (attempt == attempts) {
                break;
            }

            CharSequence activePackage = root != null ? root.getPackageName() : null;
            if (activePackage == null || !activePackage.toString().equals(packageName)) {
                XLog.i(TAG, "prepareForContactLookup: app not active, reopening " + packageName);
                service.openApp(packageName);
            } else {
                XLog.i(TAG, "prepareForContactLookup: screen not ready, pressing back");
                service.pressBack();
            }

            Thread.sleep(Math.max(settleMs, 700L));
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        boolean ready = isTargetPackage(root, packageName) && isContactLookupReady(root);
        XLog.i(TAG, "prepareForContactLookup: final ready=" + ready);
        return ready;
    }

    public static boolean scrollAndFindAndClick(
        ClawAccessibilityService service,
        LinkedHashSet<String> normalizedAliases,
        LinkedHashSet<String> digitAliases,
        int maxScrolls,
        long settleMs
    ) throws InterruptedException {
        int attempts = Math.min(Math.max(maxScrolls, 1), 20);
        String targetPackage = activePackageName(service);
        String lastScreen = safeScreenSnapshot(service);

        for (int attempt = 0; attempt <= attempts; attempt++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (!isTargetPackage(root, targetPackage)) return false;
            AccessibilityNodeInfo bestMatch = findBestVisibleContactNode(root, normalizedAliases, digitAliases);
            if (bestMatch != null) {
                XLog.i(TAG, "scrollAndFindAndClick: matched node text=" + bestMatch.getText() + " desc=" + bestMatch.getContentDescription() + " on attempt=" + attempt);
                return service.clickNode(bestMatch);
            }

            if (attempt == attempts || root == null) {
                return false;
            }

            AccessibilityNodeInfo list = findScrollableList(root);
            boolean swiped = list != null && service.performNodeAction(list, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null);
            XLog.i(TAG, "scrollAndFindAndClick: swipe attempt=" + (attempt + 1) + " result=" + swiped);
            if (!swiped) {
                return false;
            }

            Thread.sleep(settleMs);

            String currentScreen = safeScreenSnapshot(service);
            if (currentScreen != null && currentScreen.equals(lastScreen)) {
                XLog.i(TAG, "scrollAndFindAndClick: screen did not change after scroll, reached end of list");
                return false;
            }
            lastScreen = currentScreen;
        }

        return false;
    }

    public static boolean searchOrScrollAndFindAndClick(
        ClawAccessibilityService service,
        String rawQuery,
        LinkedHashSet<String> normalizedAliases,
        LinkedHashSet<String> digitAliases,
        int maxScrolls,
        long settleMs
    ) throws InterruptedException {
        String targetPackage = activePackageName(service);
        if (targetPackage == null) return false;
        for (int recoveryAttempt = 0; recoveryAttempt < 3; recoveryAttempt++) {
            if (!isTargetPackage(service.getRootInActiveWindow(), targetPackage)) return false;
            SearchAttemptResult searchResult = trySearchAndClick(service, rawQuery, normalizedAliases, digitAliases, settleMs);
            if (searchResult == SearchAttemptResult.FOUND) {
                return true;
            }
            if (searchResult == SearchAttemptResult.NO_SEARCH) {
                return isTargetPackage(service.getRootInActiveWindow(), targetPackage)
                    && scrollAndFindAndClick(service, normalizedAliases, digitAliases, maxScrolls, settleMs);
            }
            if (searchResult == SearchAttemptResult.NO_MATCH) {
                XLog.w(TAG, "searchOrScrollAndFindAndClick: no unique exact result; refusing fallback");
                return false;
            }

            XLog.i(TAG, "searchOrScrollAndFindAndClick: recovering from " + searchResult + " attempt=" + (recoveryAttempt + 1));
            String activePackage = activePackageName(service);
            if (!targetPackage.equals(activePackage)) return false;

            service.pressBack();
            Thread.sleep(Math.max(settleMs, 700L));
            if (!prepareForContactLookup(service, activePackage, 2, settleMs)) {
                break;
            }
        }
        XLog.w(TAG, "searchOrScrollAndFindAndClick: search recovery exhausted; refusing unverified results");
        return false;
    }

    public static AccessibilityNodeInfo findBestVisibleContactNode(
        AccessibilityNodeInfo root,
        Set<String> normalizedAliases,
        Set<String> digitAliases
    ) {
        if (root == null) return null;

        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        collectNodesWithText(root, normalizedAliases, digitAliases, matches);
        if (matches.isEmpty()) return null;

        AccessibilityNodeInfo match = null;
        AccessibilityNodeInfo matchedRow = null;
        for (AccessibilityNodeInfo node : matches) {
            if (!isIdentityLabel(node)) continue;
            AccessibilityNodeInfo row = clickableAncestor(node);
            if (row == null) continue;
            if (match != null && !row.equals(matchedRow)) {
                XLog.w(TAG, "findBestVisibleContactNode: ambiguous exact identity; refusing selection");
                throw new IllegalStateException("Multiple contacts match. Use a unique name or phone number.");
            }
            match = node;
            matchedRow = row;
        }
        XLog.i(TAG, "findBestVisibleContactNode: uniqueIdentity=" + (match != null));
        return match;
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        for (int depth = 0; node != null && depth < 8; depth++, node = node.getParent()) {
            if (node.isClickable() && node.isEnabled()) return node;
            if (node.isScrollable()) break;
        }
        return null;
    }

    private static boolean isEditable(AccessibilityNodeInfo node) {
        String cls = String.valueOf(node.getClassName());
        return node.isEditable() || cls.contains("EditText") || cls.contains("AutoComplete");
    }

    private static boolean isIdentityLabel(AccessibilityNodeInfo node) {
        if (!node.isVisibleToUser() || isEditable(node)) return false;
        String id = String.valueOf(node.getViewIdResourceName()).toLowerCase(java.util.Locale.ROOT);
        if (id.matches(".*(status|subtitle|preview|snippet|message|participant|search|query).*")) return false;
        if (id.contains("name") || id.contains("title")) return true;
        AccessibilityNodeInfo row = clickableAncestor(node);
        // Without title metadata only the first text label in the clickable row is an identity.
        return row != null && node.equals(firstVisibleText(row));
    }

    private static AccessibilityNodeInfo firstVisibleText(AccessibilityNodeInfo node) {
        if (node.isVisibleToUser() && !isEditable(node) && node.getText() != null && node.getText().length() > 0) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = firstVisibleText(child);
            if (found != null) return found;
        }
        return null;
    }

    private static void collectNodesWithText(
        AccessibilityNodeInfo node,
        Set<String> normalizedAliases,
        Set<String> digitAliases,
        List<AccessibilityNodeInfo> results
    ) {
        if (node == null) return;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = text != null ? text.toString() : (desc != null ? desc.toString() : null);
        if (ContactMatchUtils.matchesIdentity(label, normalizedAliases, digitAliases)) {
            results.add(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectNodesWithText(child, normalizedAliases, digitAliases, results);
            }
        }
    }

    private static SearchAttemptResult trySearchAndClick(
        ClawAccessibilityService service,
        String rawQuery,
        Set<String> normalizedAliases,
        Set<String> digitAliases,
        long settleMs
    ) throws InterruptedException {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return SearchAttemptResult.SEARCH_UI_MISSING;
        }

        String targetPackage = String.valueOf(root.getPackageName());
        AccessibilityNodeInfo searchField = UiActionMatchUtils.findBestSearchField(root);
        if (searchField == null && dismissBlockingOverlay(service, root, settleMs)) {
            root = service.getRootInActiveWindow();
            searchField = UiActionMatchUtils.findBestSearchField(root);
        }
        boolean tappedSearchAction = false;
        if (searchField == null) {
            AccessibilityNodeInfo searchAction = UiActionMatchUtils.findBestSearchAction(root);
            if (searchAction != null) {
                boolean clicked = service.clickNode(searchAction);
                XLog.i(TAG, "trySearchAndClick: tapped search action, clicked=" + clicked);
                tappedSearchAction = clicked;
                if (clicked) {
                    Thread.sleep(Math.max(settleMs, 500L));
                    root = service.getRootInActiveWindow();
                    searchField = UiActionMatchUtils.findBestSearchField(root);
                }
            }
        }

        if (searchField == null) {
            XLog.i(TAG, "trySearchAndClick: no search field available");
            return tappedSearchAction ? SearchAttemptResult.SEARCH_UI_MISSING : SearchAttemptResult.NO_SEARCH;
        }

        if (!targetPackage.equals(String.valueOf(searchField.getPackageName()))) return SearchAttemptResult.NO_MATCH;
        if (!setText(service, searchField, rawQuery)) {
            XLog.i(TAG, "trySearchAndClick: failed to type query into search field");
            return SearchAttemptResult.TYPE_FAILED;
        }

        Thread.sleep(Math.max(settleMs, 600L));

        root = service.getRootInActiveWindow();
        if (!isTargetPackage(root, targetPackage)) return SearchAttemptResult.NO_MATCH;
        AccessibilityNodeInfo bestMatch = findBestVisibleResultNode(root, normalizedAliases, digitAliases);
        if (bestMatch != null) {
            XLog.i(TAG, "trySearchAndClick: matched filtered result text=" + bestMatch.getText() + " desc=" + bestMatch.getContentDescription());
            return service.clickNode(bestMatch) ? SearchAttemptResult.FOUND : SearchAttemptResult.NO_MATCH;
        }

        XLog.i(TAG, "trySearchAndClick: no filtered result matched query");
        return SearchAttemptResult.NO_MATCH;
    }

    private static String activePackageName(ClawAccessibilityService service) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            return null;
        }
        return root.getPackageName().toString();
    }

    private static boolean setText(ClawAccessibilityService service, AccessibilityNodeInfo node, String text) throws InterruptedException {
        if (node == null) return false;
        String targetPackage = String.valueOf(node.getPackageName());
        service.performNodeAction(node, AccessibilityNodeInfo.ACTION_FOCUS, null);
        service.performNodeAction(node, AccessibilityNodeInfo.ACTION_CLICK, null);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!service.performNodeAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false;
        for (int attempt = 0; attempt < 5; attempt++) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (!isTargetPackage(root, targetPackage)) return false;
            AccessibilityNodeInfo field = UiActionMatchUtils.findBestSearchField(root);
            String actual = field != null && field.getText() != null ? field.getText().toString() : "";
            if (actual.replaceAll("\\p{Cf}", "").trim().equals(text.replaceAll("\\p{Cf}", "").trim())) {
                XLog.i(TAG, "setText: query verified from fresh root, attempt=" + attempt);
                return true;
            }
            Thread.sleep(150);
        }
        XLog.w(TAG, "setText: query not confirmed on fresh search field");
        return false;
    }

    private static void clearText(ClawAccessibilityService service, AccessibilityNodeInfo node) {
        Bundle clearArgs = new Bundle();
        clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        service.performNodeAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
    }

    private static AccessibilityNodeInfo findBestVisibleResultNode(
        AccessibilityNodeInfo root,
        Set<String> normalizedAliases,
        Set<String> digitAliases
    ) {
        AccessibilityNodeInfo candidate = findBestVisibleContactNode(root, normalizedAliases, digitAliases);
        if (candidate == null) return null;

        CharSequence className = candidate.getClassName();
        String classNameString = className != null ? className.toString() : "";
        if (candidate.isEditable() || classNameString.contains("EditText") || classNameString.contains("AutoComplete")) {
            return null;
        }
        return candidate;
    }

    public static boolean isTargetPackage(AccessibilityNodeInfo root, String targetPackage) {
        return root != null && targetPackage != null && targetPackage.equals(String.valueOf(root.getPackageName()));
    }

    public static boolean isTargetChat(AccessibilityNodeInfo root, String targetPackage, String contact) {
        if (!isTargetPackage(root, targetPackage) || UiActionMatchUtils.findBestSearchField(root) != null) return false;
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (!hasComposer(root, screen)) return false;
        List<AccessibilityNodeInfo> titles = new ArrayList<>();
        collectToolbarTitles(root, screen, titles);
        if (titles.isEmpty()) return false;
        // Select the primary (topmost) label, not a group member/status subtitle.
        AccessibilityNodeInfo title = titles.get(0);
        Rect bestBounds = new Rect();
        title.getBoundsInScreen(bestBounds);
        for (AccessibilityNodeInfo node : titles) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (bounds.top < bestBounds.top) {
                title = node;
                bestBounds = bounds;
            }
        }
        boolean match = ContactMatchUtils.matchesIdentity(String.valueOf(title.getText()),
            ContactMatchUtils.buildNormalizedAliases(contact), ContactMatchUtils.buildDigitAliases(contact));
        XLog.i(TAG, "isTargetChat: titleId=" + title.getViewIdResourceName() + ", composer=true, identity=" + match);
        return match;
    }

    private static void collectToolbarTitles(AccessibilityNodeInfo node, Rect screen, List<AccessibilityNodeInfo> titles) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.top >= screen.top && bounds.bottom <= screen.top + Math.min(300, screen.height() / 4)
            && isIdentityLabel(node) && node.getText() != null) titles.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) collectToolbarTitles(child, screen, titles);
        }
    }

    private static boolean hasComposer(AccessibilityNodeInfo node, Rect screen) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        String id = String.valueOf(node.getViewIdResourceName()).toLowerCase(java.util.Locale.ROOT);
        if (node.isVisibleToUser() && node.isEnabled() && isEditable(node) && !bounds.isEmpty()
            && bounds.top > screen.top + screen.height() / 3 && !id.matches(".*(search|query|find).*")) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null && hasComposer(child, screen)) return true;
        }
        return false;
    }

    private static AccessibilityNodeInfo findScrollableList(AccessibilityNodeInfo node) {
        if (node.isVisibleToUser() && node.isScrollable() && !isEditable(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            AccessibilityNodeInfo found = findScrollableList(child);
            if (found != null) return found;
        }
        return null;
    }

    public static boolean isContactLookupReady(AccessibilityNodeInfo root) {
        if (root == null) return false;

        if (UiActionMatchUtils.findBestSearchField(root) != null) return true;
        Rect screen = new Rect();
        root.getBoundsInScreen(screen);
        if (hasComposer(root, screen)) return false;
        if (UiActionMatchUtils.findBestSearchAction(root) != null) return true;

        int[] metrics = new int[3];
        collectVisibleListSignals(root, metrics);
        int visibleTextRows = metrics[0];
        int clickableRows = metrics[1];
        int scrollableContainers = metrics[2];

        return scrollableContainers > 0 && visibleTextRows >= 3 && clickableRows >= 2;
    }

    private static String safeScreenSnapshot(ClawAccessibilityService service) {
        try {
            return service.getScreenTree();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean dismissBlockingOverlay(
        ClawAccessibilityService service,
        AccessibilityNodeInfo root,
        long settleMs
    ) throws InterruptedException {
        AccessibilityNodeInfo closeAction = findBlockingOverlayCloseAction(root);
        if (closeAction == null) {
            return false;
        }
        boolean clicked = service.clickNode(closeAction);
        XLog.i(TAG, "dismissBlockingOverlay: close action clicked=" + clicked);
        if (clicked) {
            Thread.sleep(Math.max(settleMs, 500L));
        }
        return clicked;
    }

    private static AccessibilityNodeInfo findBlockingOverlayCloseAction(AccessibilityNodeInfo root) {
        if (root == null) return null;

        Rect screenBounds = new Rect();
        root.getBoundsInScreen(screenBounds);
        if (screenBounds.isEmpty()) return null;

        Candidate best = new Candidate();
        collectCloseCandidates(root, screenBounds, best);
        return best.score >= 70 ? best.node : null;
    }

    private static void collectCloseCandidates(
        AccessibilityNodeInfo node,
        Rect screenBounds,
        Candidate best
    ) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) return;

        int score = scoreCloseCandidate(node, screenBounds);
        if (score > best.score) {
            best.score = score;
            best.node = node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectCloseCandidates(child, screenBounds, best);
            }
        }
    }

    private static int scoreCloseCandidate(AccessibilityNodeInfo node, Rect screenBounds) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty() || bounds.width() <= 0 || bounds.height() <= 0) {
            return Integer.MIN_VALUE;
        }

        if (bounds.top > screenBounds.top + (int) (screenBounds.height() * 0.45f)) {
            return Integer.MIN_VALUE;
        }

        String viewId = node.getViewIdResourceName();
        String className = node.getClassName() != null ? node.getClassName().toString() : "";
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();

        boolean actionable = node.isClickable() || node.isLongClickable();
        if (!actionable) {
            return Integer.MIN_VALUE;
        }

        boolean explicitClose = false;
        if (viewId != null && (viewId.toLowerCase().contains("close") || viewId.toLowerCase().contains("dismiss"))) {
            explicitClose = true;
        }
        if (matchesCloseHint(text) || matchesCloseHint(desc)) {
            explicitClose = true;
        }
        if (!explicitClose) {
            return Integer.MIN_VALUE;
        }

        int score = 10;
        score += 100;
        if (className.contains("ImageButton") || className.contains("ImageView")) {
            score += 20;
        }
        if (bounds.right >= screenBounds.right - (int) (screenBounds.width() * 0.1f)) {
            score += 18;
        }
        if (bounds.top <= screenBounds.top + (int) (screenBounds.height() * 0.2f)) {
            score += 14;
        }
        if (bounds.width() <= screenBounds.width() * 0.25f && bounds.height() <= screenBounds.height() * 0.15f) {
            score += 12;
        }
        return score;
    }

    private static boolean matchesCloseHint(CharSequence value) {
        if (value == null || value.length() == 0) return false;
        for (String hint : CLOSE_HINTS) {
            if (UiTextMatchUtils.matchesRelaxed(value, hint)) {
                return true;
            }
        }
        return false;
    }

    private static final class Candidate {
        private AccessibilityNodeInfo node;
        private int score = Integer.MIN_VALUE;
    }

    private static void collectVisibleListSignals(AccessibilityNodeInfo node, int[] metrics) {
        if (node == null) return;

        if (node.isVisibleToUser()) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String className = node.getClassName() != null ? node.getClassName().toString() : "";
            boolean imageLike = className.contains("Image")
                || className.contains("Photo")
                || className.contains("Thumbnail");
            boolean rowLike = !node.isEditable()
                && !className.contains("EditText")
                && !imageLike
                && !className.contains("Toolbar")
                && !className.contains("ActionBar");
            boolean hasUsableLabel = text != null && text.length() > 0;

            if (rowLike && hasUsableLabel) {
                metrics[0]++;
                if (node.isClickable()) {
                    metrics[1]++;
                }
            }
            if (node.isScrollable()) {
                metrics[2]++;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectVisibleListSignals(child, metrics);
            }
        }
    }
}
