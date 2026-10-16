package com.shieldchild.app

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.shieldchild.app.data.PreferencesRepository

import com.shieldchild.app.engine.CurfewLockLauncher

class ShieldApplication : Application() {

    lateinit var preferencesRepository: PreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferencesRepository = PreferencesRepository(this)
        createNotificationChannels()

        // Immediate reboot / process launch lockdown enforcement
        if (preferencesRepository.isSetupCompleted()) {
            val policy = preferencesRepository.getCurfewPolicy()
            val isDailyExceeded = preferencesRepository.isDailyLimitExceededToday()
            val reason = CurfewLockLauncher.evaluateRebootLockReason(
                policy = policy,
                isDailyExceeded = isDailyExceeded,
                isOverrideActive = preferencesRepository.isCurfewOverrideActive()
            )
            if (reason != null) {
                CurfewLockLauncher.launch(this, reason)
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_MONITOR,
                "ShieldChild Protection Guard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors screen time boundaries and protects device settings."
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "ShieldChild Boundary Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for time warnings and parent approval requests."
                setShowBadge(true)
            }

            val lockChannel = NotificationChannel(
                CHANNEL_LOCK,
                "ShieldChild Curfew Lock",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Immediate full-screen lockdown enforcement for curfew and limits."
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            notificationManager.createNotificationChannel(alertChannel)
            notificationManager.createNotificationChannel(lockChannel)
        }
    }

    companion object {
        const val CHANNEL_MONITOR = "shield_monitor_channel"
        const val CHANNEL_ALERTS = "shield_alerts_channel"
        const val CHANNEL_LOCK = "shield_lock_channel"

        lateinit var instance: ShieldApplication
            private set
    }
}
