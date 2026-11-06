// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.service.PermissionDialogUnavailable
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog

/** Tracks cancellation and observed permission boundaries; choices belong to the conversation. */
class PermissionConsentSession {
    @Volatile private var cancelled = false
    private val revisions = java.util.concurrent.atomic.AtomicInteger(0)
    val revision: Int get() = revisions.get()
    var needsClarification: Boolean = false
        private set

    @Synchronized
    fun cancel() {
        cancelled = true
    }

    private fun ensureActive() {
        if (cancelled || Thread.currentThread().isInterrupted) throw InterruptedException("Task cancelled")
    }

    fun <T> run(block: () -> T): T {
        current.set(this)
        active.set(this)
        io.agents.pokeclaw.service.PermissionDialog.clearObservation()
        XLog.i(TAG, "Permission choices use conversation; no consent polling or overlay")
        return try { block() } finally {
            cancel()
            io.agents.pokeclaw.service.PermissionDialog.clearObservation()
            active.compareAndSet(this, null)
            current.remove()
        }
    }

    companion object {
        private const val TAG = "PermissionConsent"
        private val current = ThreadLocal<PermissionConsentSession>()
        private val active = java.util.concurrent.atomic.AtomicReference<PermissionConsentSession?>()

        @JvmStatic
        fun notePermissionWindowChanged() {
            active.get()?.let {
                val revision = it.revisions.incrementAndGet()
                XLog.d(TAG, "Permission window event invalidated queued actions, revision=$revision")
            }
        }

        @JvmStatic
        fun checkCancellation() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("Task cancelled")
            current.get()?.ensureActive()
        }

        fun dispatchPermissionAction(action: () -> Boolean): Boolean {
            val session = current.get()
            return if (session == null) {
                checkCancellation()
                action()
            } else synchronized(session) {
                session.ensureActive()
                action()
            }
        }

        @JvmStatic
        fun currentRevision(): Int = current.get()?.revision ?: 0

        @JvmStatic
        fun recordObservation(permissionOrUnavailable: Boolean) {
            val session = current.get() ?: return
            session.ensureActive()
            if (permissionOrUnavailable || session.needsClarification) session.revisions.incrementAndGet()
            session.needsClarification = permissionOrUnavailable
            XLog.d(TAG, "Observation: permissionOrUnavailable=$permissionOrUnavailable, revision=${session.revision}")
        }

        @JvmStatic
        fun invalidatePermissionTargets() {
            current.get()?.let {
                it.revisions.incrementAndGet()
                XLog.d(TAG, "Permission action attempted; queued targets invalidated, revision=${it.revision}")
            }
        }

        fun executeGuarded(action: () -> ToolResult): ToolResult {
            checkCancellation()
            val result = try {
                action()
            } catch (e: PermissionDialogUnavailable) {
                recordObservation(true)
                XLog.w(TAG, "Exposed UI action unavailable: reason=${e.reason}")
                ToolResult.error(e.message ?: "Screen unavailable; read a fresh screen before acting.")
            }
            checkCancellation()
            return result
        }
    }
}
