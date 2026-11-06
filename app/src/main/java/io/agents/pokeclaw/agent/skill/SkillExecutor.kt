// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.skill

import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.utils.XLog

/**
 * Executes skill steps deterministically using the ToolRegistry.
 *
 * Flow:
 * 1. Execute each step's tool with resolved parameters
 * 2. If a step fails and is not optional, retry up to step.retries times
 * 3. If retries exhausted, return failure with fallbackGoal for LLM agent loop
 *
 * Architecture reference:
 * - Server-side deterministic execution pattern
 * - Hardcoded step sequence pattern
 */
class SkillExecutor {

    /**
     * Execute a skill with resolved parameters.
     *
     * @param skill the skill definition
     * @param params user-provided parameter values (e.g., {"query": "cat videos"})
     * @param onProgress callback for each step progress
     * @return SkillResult indicating success or failure
     */
    fun execute(
        skill: Skill,
        params: Map<String, String>,
        onProgress: ((step: Int, total: Int, description: String) -> Unit)? = null
    ): SkillResult {
        XLog.i(TAG, "Executing skill: ${skill.id} with params: $params")
        val totalSteps = skill.steps.size
        var stepsUsed = 0
        val consentRevision = io.agents.pokeclaw.agent.PermissionConsentSession.currentRevision()

        for ((index, step) in skill.steps.withIndex()) {
            stepsUsed = index + 1
            val stepNum = index + 1
            onProgress?.invoke(stepNum, totalSteps, step.description)
            XLog.d(TAG, "Step $stepNum/$totalSteps: ${step.toolName} — ${step.description}")

            // Resolve parameter placeholders in tool params
            val resolvedParams = resolveParams(step.params, params)

            // Execute with retries
            var succeeded = false
            for (attempt in 1..step.retries) {
                if (io.agents.pokeclaw.agent.PermissionConsentSession.currentRevision() != consentRevision) {
                    XLog.i(TAG, "Skill skipped queued action after permission window changed")
                    return SkillResult(success = false, stepsUsed = stepsUsed,
                        message = "Permission screen changed; remaining skill actions were not executed. Read a fresh screen and replan.")
                }
                try {
                    val toolParams = resolvedParams.mapValues { (_, v) -> v as Any }
                    val result = ToolRegistry.getInstance().executeTool(step.toolName, toolParams)
                    if (io.agents.pokeclaw.agent.PermissionConsentSession.currentRevision() != consentRevision) {
                        XLog.i(TAG, "Skill stopped at permission/observation boundary; queued steps invalidated")
                        return SkillResult(success = false, stepsUsed = stepsUsed,
                            message = "A permission or unavailable-screen boundary invalidated the queued steps. Read a fresh screen, use the user's clear choice or ask in chat; do not replay steps or assume a grant.")
                    }

                    if (result.isSuccess) {
                        XLog.d(TAG, "Step $stepNum OK: ${result.data?.take(100)}")
                        succeeded = true
                        break
                    } else {
                        XLog.w(TAG, "Step $stepNum failed (attempt $attempt/${step.retries}): ${result.error}")
                        if (attempt < step.retries) {
                            Thread.sleep(500) // brief pause before retry
                        }
                    }
                } catch (e: io.agents.pokeclaw.service.PermissionDialogUnavailable) {
                    throw e
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    XLog.e(TAG, "Step $stepNum exception (attempt $attempt/${step.retries})", e)
                    if (attempt < step.retries) {
                        Thread.sleep(500)
                    }
                }
            }

            if (!succeeded) {
                if (step.optional) {
                    XLog.i(TAG, "Step $stepNum failed but optional, continuing")
                    continue
                }
                // Non-optional step failed after retries
                XLog.w(TAG, "Skill ${skill.id} failed at step $stepNum: ${step.description}")
                return SkillResult(
                    success = false,
                    stepsUsed = stepsUsed,
                    fallbackUsed = false,
                    message = "Failed at step $stepNum: ${step.description}. " +
                              "Fallback goal: ${resolveTemplate(skill.fallbackGoal, params)}"
                )
            }
        }

        XLog.i(TAG, "Skill ${skill.id} completed in $stepsUsed steps")
        return SkillResult(
            success = true,
            stepsUsed = stepsUsed,
            message = "Skill '${skill.name}' completed successfully in $stepsUsed steps."
        )
    }

    /**
     * Replace {param_name} placeholders in step params with actual values.
     */
    private fun resolveParams(
        stepParams: Map<String, String>,
        userParams: Map<String, String>
    ): Map<String, String> {
        return stepParams.mapValues { (_, template) ->
            resolveTemplate(template, userParams)
        }
    }

    private fun resolveTemplate(template: String, params: Map<String, String>): String {
        var result = template
        for ((key, value) in params) {
            result = result.replace("{$key}", value)
        }
        return result
    }

    companion object {
        private const val TAG = "SkillExecutor"
    }
}
