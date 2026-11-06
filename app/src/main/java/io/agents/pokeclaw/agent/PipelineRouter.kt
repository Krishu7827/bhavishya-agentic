// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import android.content.Context
import android.content.Intent
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.utils.XLog

/**
 * Routes user tasks to the model without deterministic parser or skill shortcuts.
 * Legacy route types and execution helpers remain for caller compatibility.
 */
class PipelineRouter(private val context: Context) {

    sealed class Route {
        /** Tier 1: Execute Android intent directly */
        data class DirectIntent(val intent: Intent, val description: String) : Route()

        /** Tier 1: Execute a tool directly (e.g., screenshot, back, home) */
        data class DirectTool(val toolName: String, val params: Map<String, Any>, val description: String) : Route()

        /** Tier 2: Execute a registered skill */
        data class Skill(val skillId: String, val params: Map<String, String>, val description: String) : Route()

        /** Tier 2/3: Run the full agent loop */
        data class AgentLoop(val task: String, val app: String? = null) : Route()

        /** Tier 2: Pure chat response (no phone control) */
        data class Chat(val task: String) : Route()
    }

    /**
     * Pass the unchanged user task to the model's agent loop.
     *
     * @param task user's task text
     * @return the routing decision
     */
    fun route(task: String): Route {
        XLog.i(TAG, "Routing user request directly to model")
        return Route.AgentLoop(task)
    }

    /**
     * Execute a Tier 1 direct intent.
     */
    fun executeIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            XLog.i(TAG, "Executed intent: ${intent.action}")
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to execute intent: ${intent.action}", e)
        }
    }

    /**
     * Execute a Tier 1 direct tool call.
     */
    fun executeTool(toolName: String, params: Map<String, Any>): ToolResult {
        val result = ToolRegistry.getInstance().executeTool(toolName, params)
        XLog.i(TAG, "Executed tool: $toolName → ${if (result.isSuccess) "success" else result.error}")
        return result
    }

    companion object {
        private const val TAG = "PipelineRouter"
    }
}
