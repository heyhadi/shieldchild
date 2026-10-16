package com.shieldchild.app

import com.shieldchild.app.i18n.str
import com.shieldchild.app.R
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
                access = preferencesRepository.getAccessState()
            )
            if (reason != null) {
                if (com.shieldchild.app.engine.ProfileGuard.isScreenInteractiveForProfile(this)) {
                    CurfewLockLauncher.launch(this, reason)
                }
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_MONITOR,
                str(R.string.shieldchild_protection_guard),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = str(R.string.monitors_screen_time_boundaries_and)
                setShowBadge(false)
            }

            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                str(R.string.shieldchild_boundary_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = str(R.string.notifications_for_time_warnings_and)
                setShowBadge(true)
            }

            val lockChannel = NotificationChannel(
                CHANNEL_LOCK,
                str(R.string.shieldchild_curfew_lock),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = str(R.string.immediate_full_screen_lockdown_enforcement)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
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
