package com.shieldchild.app.engine

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.shieldchild.app.R
import com.shieldchild.app.ShieldApplication
import com.shieldchild.app.data.DeviceCurfewPolicy
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.ui.curfew.CurfewLockActivity
import java.util.Calendar

object CurfewLockLauncher {

    const val NOTIFICATION_LOCK_ID = 2002

    /**
     * Determines whether the device should currently be locked based on policy and state upon reboot or app launch.
     */
    fun evaluateRebootLockReason(
        policy: DeviceCurfewPolicy,
        isDailyExceeded: Boolean,
        engine: PolicyEngine = PolicyEngine(),
        cal: Calendar = Calendar.getInstance(),
        isOverrideActive: Boolean = false
    ): LockReason? {
        if (policy.isInstantLocked) {
            return LockReason.PARENT_INSTANT_FREEZE
        }
        if (isOverrideActive) {
            return null
        }
        if (policy.isCurfewScheduled && engine.isWithinCurfewWindow(policy, cal)) {
            return LockReason.SCHEDULED_BEDTIME_CURFEW
        }
        val dayNames = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val currentDay = dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1]
        if (isDailyExceeded && policy.isDailyLimitEnabled && !policy.unlimitedDays.contains(currentDay)) {
            return LockReason.TOTAL_SCREEN_TIME_EXCEEDED
        }
        return null
    }

    /**
     * Immediately brings the CurfewLockActivity to the front using dual mechanisms:
     * 1. Direct Activity Intent with top/front flags.
     * 2. High-priority FullScreenIntent Notification (required by Android 10+ to break through background blocks on reboot).
     */
    fun launch(context: Context, reason: LockReason) {
        val lockIntent = Intent(context, CurfewLockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(CurfewLockActivity.EXTRA_LOCK_REASON, reason.name)
        }

        // 1. Direct startActivity attempt
        try {
            context.startActivity(lockIntent)
        } catch (_: Exception) {}

        // 2. Full-Screen Intent Notification for 100% reliability after device reboot
        try {
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_LOCK_ID,
                lockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (nm != null) {
                val notification = NotificationCompat.Builder(context, ShieldApplication.CHANNEL_LOCK)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle("Curfew Lockdown Active")
                    .setContentText(getLockDescription(reason))
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setFullScreenIntent(pendingIntent, true)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build()

                nm.notify(NOTIFICATION_LOCK_ID, notification)
            }
        } catch (_: Exception) {}
    }

    fun dismissLockNotification(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_LOCK_ID)
        } catch (_: Exception) {}
    }

    private fun getLockDescription(reason: LockReason): String {
        return when (reason) {
            LockReason.PARENT_INSTANT_FREEZE -> "Instant lock activated by parent"
            LockReason.SCHEDULED_BEDTIME_CURFEW -> "Bedtime curfew is in effect"
            LockReason.TOTAL_SCREEN_TIME_EXCEEDED -> "Daily screen time limit reached"
        }
    }
}
