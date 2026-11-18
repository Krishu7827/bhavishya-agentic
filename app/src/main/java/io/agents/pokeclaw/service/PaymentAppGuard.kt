package io.agents.pokeclaw.service

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.R
import io.agents.pokeclaw.utils.XLog

/** Disables only our own service; never interacts with a payment app's controls. */
class PaymentAppGuard(private val service: ClawAccessibilityService) {
    companion object {
        private const val TAG = "PaymentAppGuard"
        private const val DELAY_MS = 2_000L
        private const val CHANNEL_ID = "bhavishya_payment_safety"
        private const val NOTIFICATION_ID = 1006

        // Exact package IDs, not display-name substrings. See README for coverage limits.
        private val KNOWN_APPS = mapOf(
            "com.phonepe.app" to "PhonePe",
            "com.google.android.apps.nbu.paisa.user" to "Google Pay",
            "net.one97.paytm" to "Paytm",
            "in.org.npci.upiapp" to "BHIM",
            "com.sbi.lotusintouch" to "YONO SBI",
            "com.sbi.SBIFreedomPlus" to "YONO Lite SBI",
            "com.hdfcbank.android.now" to "HDFC Bank",
            "com.csam.icici.bank.imobile" to "ICICI iMobile",
            "com.axis.mobile" to "Axis Mobile",
            "com.msf.kbank.mobile" to "Kotak Bank (Old)",
            "com.idfcfirstbank.optimus" to "IDFC FIRST Bank",
            "com.Version1" to "PNB ONE",
            "com.bankofbaroda.mconnect" to "bob World",
            "com.canarabank.mobility" to "Canara ai1",
            "com.infrasoft.uboi" to "Union ease",
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastForeground: String? = null
    private var pendingPackage: String? = null
    private var pendingDisable: Runnable? = null
    private var disabling = false

    fun onConnected() {
        XLog.i(TAG, "onConnected: delayMs=$DELAY_MS; UPI handlers + ${KNOWN_APPS.size} known packages")
        service.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        inspectForeground()
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            inspectForeground()
        }
    }

    fun stop() {
        cancelPending("service interrupted/unbound/destroyed")
        lastForeground = null
    }

    private fun foregroundPackage(): String? {
        if (!service.getSystemService(PowerManager::class.java).isInteractive ||
            service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val windows = service.windows
        try {
            val focused = windows.firstOrNull { it.isFocused } ?: return null
            if (focused.type != AccessibilityWindowInfo.TYPE_APPLICATION) return null
            val root = focused.root ?: return null
            try {
                return root.packageName?.toString()
            } finally {
                root.recycle()
            }
        } finally {
            windows.forEach { it.recycle() }
        }
    }

    private fun paymentReason(pkg: String): String? {
        if (pkg == service.packageName) return null
        KNOWN_APPS[pkg]?.let { return "known app: $it" }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay"))
            .addCategory(Intent.CATEGORY_DEFAULT).setPackage(pkg)
        return if (service.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo?.packageName == pkg && it.activityInfo.exported }) {
            "registered UPI handler"
        } else null
    }

    private fun inspectForeground() {
        if (disabling) return
        try {
            val pkg = foregroundPackage()
            if (pkg == lastForeground) return
            XLog.i(TAG, "foreground: $lastForeground -> $pkg")
            lastForeground = pkg
            cancelPending("foreground changed")
            if (pkg == null) return
            val reason = paymentReason(pkg)
            if (reason == null) {
                XLog.d(TAG, "not a recognized payment app: package=$pkg")
                return
            }
            XLog.i(TAG, "schedule: package=$pkg, reason=$reason, delayMs=$DELAY_MS")
            pendingPackage = pkg
            pendingDisable = Runnable { disableIfStillForeground(pkg) }.also {
                handler.postDelayed(it, DELAY_MS)
            }
        } catch (e: Exception) {
            lastForeground = null
            cancelPending("foreground inspection failed")
            XLog.e(TAG, "Unable to inspect foreground payment app", e)
        }
    }

    private fun cancelPending(reason: String) {
        pendingDisable?.let {
            handler.removeCallbacks(it)
            XLog.i(TAG, "cancel: package=$pendingPackage, reason=$reason")
        }
        pendingDisable = null
        pendingPackage = null
    }

    private fun disableIfStillForeground(pkg: String) {
        pendingDisable = null
        pendingPackage = null
        try {
            val foreground = foregroundPackage()
            XLog.i(TAG, "timer expired: expected=$pkg, foreground=$foreground")
            if (foreground != pkg) {
                lastForeground = null
                XLog.i(TAG, "cancel: payment app no longer focused/unlocked")
                return
            }
            disabling = true
            val app = service.applicationContext
            try {
                val model = ClawApplication.appViewModelInstance
                XLog.i(TAG, "before disable: taskRunning=${model.isTaskRunning()}")
                if (model.isTaskRunning()) model.stopTask()
            } catch (e: Exception) {
                XLog.e(TAG, "Task cancellation unavailable; still disabling accessibility", e)
            }
            XLog.i(TAG, "disableSelf: payment package=$pkg")
            service.disableSelf()
            // Confirmation must survive service teardown and must not announce OFF prematurely.
            confirmDisabled(app, 0)
        } catch (e: Exception) {
            disabling = false
            XLog.e(TAG, "Could not disable accessibility for payment package=$pkg", e)
            Toast.makeText(service, R.string.payment_accessibility_disable_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDisabled(context: Context, attempt: Int) {
        val enabled = ClawAccessibilityService.isEnabledInSettings(context)
        XLog.i(TAG, "disable confirmation: enabled=$enabled, attempt=$attempt")
        if (!enabled) {
            notifyDisabled(context)
        } else if (attempt < 10) {
            handler.postDelayed({ confirmDisabled(context, attempt + 1) }, 200)
        } else {
            XLog.e(TAG, "Accessibility disable not confirmed; manual Settings action needed")
            Toast.makeText(context, R.string.payment_accessibility_disable_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun notifyDisabled(context: Context) {
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, context.getString(R.string.payment_safety_channel), NotificationManager.IMPORTANCE_DEFAULT
            ))
            val allowed = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
            XLog.i(TAG, "notifyDisabled: notificationsAllowed=$allowed")
            if (!allowed) {
                XLog.w(TAG, "Off notification blocked; showing Toast with Settings guidance")
                Toast.makeText(context, R.string.payment_accessibility_off_notifications_blocked, Toast.LENGTH_LONG).show()
                return
            }
            val settings = PendingIntent.getActivity(context, NOTIFICATION_ID,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bhavishya)
                .setContentTitle(context.getString(R.string.payment_accessibility_off_title))
                .setContentText(context.getString(R.string.payment_accessibility_off_text))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.payment_accessibility_off_text)))
                .setContentIntent(settings)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build())
            XLog.i(TAG, "Off notification posted; re-enable requires user Settings action")
        } catch (e: Exception) {
            XLog.e(TAG, "Off notification failed", e)
            Toast.makeText(context, R.string.payment_accessibility_off_notifications_blocked, Toast.LENGTH_LONG).show()
        }
    }
}
