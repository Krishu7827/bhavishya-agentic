// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl;

import io.agents.pokeclaw.ClawApplication;
import io.agents.pokeclaw.R;
import io.agents.pokeclaw.service.ClawAccessibilityService;
import io.agents.pokeclaw.tool.BaseTool;
import io.agents.pokeclaw.tool.ToolParameter;
import io.agents.pokeclaw.tool.ToolResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class GetScreenInfoTool extends BaseTool {

    @Override
    public String getName() {
        return "get_screen_info";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_get_screen_info);
    }

    @Override
    public String getDescriptionEN() {
        return "Get the current screen's UI elements. Each element has a node ID (e.g. [n3]) that can be used with tap_node. Do not cache this result — node IDs change on each call.";
    }

    @Override
    public String getDescriptionCN() {
        return "Get the current screen's UI elements. Each element has a node ID (e.g. [n3]) that can be used with tap_node. Do not cache this result — node IDs change on each call.";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.emptyList();
    }

    public static final String SYSTEM_DIALOG_BLOCKED = "__SYSTEM_DIALOG_BLOCKED__";

    /**
     * Switch to full node tree mode (includes all nodes and all attributes, for debugging).
     * false = compact mode (default, saves tokens); true = full mode.
     */
    public static boolean useFullTree = false;

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = requireAccessibilityService();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        try {
            String tree = useFullTree ? service.getScreenTreeFull() : service.getScreenTree();
            if (tree == null || tree.trim().isEmpty()) {
                throw new io.agents.pokeclaw.service.PermissionDialogUnavailable("exposed_tree_empty");
            }
            return ToolResult.success(tree);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            io.agents.pokeclaw.service.PermissionDialog.invalidateObservation();
            String reason = e instanceof io.agents.pokeclaw.service.PermissionDialogUnavailable
                    ? ((io.agents.pokeclaw.service.PermissionDialogUnavailable) e).getReason()
                    : "window_read_failed";
            io.agents.pokeclaw.utils.XLog.w("PermissionDialog", "Screen observation unavailable: " + reason);
            return ToolResult.error("Screen observation unavailable (" + reason + "). This does not prove a protected/system dialog. Request a fresh screen, or explain the limitation and ask for manual help if it persists.");
        }
    }
}
