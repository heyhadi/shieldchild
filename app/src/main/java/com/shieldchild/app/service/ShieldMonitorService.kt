package com.shieldchild.app.service

import com.shieldchild.app.i18n.str
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.media.AudioManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.shieldchild.app.R
import com.shieldchild.app.ShieldApplication
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.data.LockState
import com.shieldchild.app.data.PreferencesRepository
import com.shieldchild.app.engine.DailyUsageSummary
import com.shieldchild.app.engine.PolicyEngine
import com.shieldchild.app.engine.UsageMonitor
import com.shieldchild.app.overlay.AppShieldOverlayManager
import com.shieldchild.app.ui.curfew.CurfewLockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ShieldMonitorService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var usageMonitor: UsageMonitor
    private lateinit var policyEngine: PolicyEngine
    private lateinit var overlayManager: AppShieldOverlayManager

    private var currentForegroundPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        preferencesRepository = ShieldApplication.instance.preferencesRepository
        usageMonitor = UsageMonitor(this)
        policyEngine = PolicyEngine()
        overlayManager = AppShieldOverlayManager(this)

        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        startMonitoringLoop()
    }

    /** Handing the phone back usually means pressing power: end a parent session then. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!preferencesRepository.hasParentSessionRecord()) return
            // The screen also goes off at the ear during a call; keep the parent's session.
            val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val inCall = audio?.mode == AudioManager.MODE_IN_CALL || audio?.mode == AudioManager.MODE_IN_COMMUNICATION
            if (inCall) return
            preferencesRepository.endParentSession()
            serviceScope.launch { evaluateCurrentState() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FOREGROUND_PACKAGE_CHANGED -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)
                if (pkg != null) {
                    currentForegroundPackage = pkg
                    serviceScope.launch { evaluateCurrentState() }
                }
            }
            ACTION_RECHECK_POLICIES -> {
                serviceScope.launch { evaluateCurrentState() }
            }
            ACTION_END_PARENT_SESSION -> {
                preferencesRepository.endParentSession()
                serviceScope.launch { evaluateCurrentState() }
            }
        }
        return START_STICKY
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            while (isActive) {
                evaluateCurrentState()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun evaluateCurrentState() {
        // Idle or 30-minute limit reached: close the session so its time is excluded and the lock returns.
        if (preferencesRepository.hasParentSessionRecord() && !preferencesRepository.isParentSessionActive()) {
            preferencesRepository.endParentSession()
        }
        updateParentSessionNotification()
        warnBeforeGrantEnds()

        val policy = preferencesRepository.getCurfewPolicy()
        val appLimits = preferencesRepository.getAppLimits()
        val fgPkg = currentForegroundPackage ?: usageMonitor.getForegroundPackageName()
        val usage = usageMonitor.queryDailyUsage()

        val lockState = policyEngine.evaluateLockState(
            policy = policy,
            appLimits = appLimits,
            usageSummary = usage,
            foregroundPackage = fgPkg,
            access = preferencesRepository.getAccessState()
        )

        // Keep the persisted flag in step with the live result, so raising the limit
        // or adding time unlocks the accessibility/boot paths too.
        val isLimitLock = lockState is LockState.CurfewLock && lockState.reason == LockReason.TOTAL_SCREEN_TIME_EXCEEDED
        if (isLimitLock != preferencesRepository.isDailyLimitExceededToday()) {
            preferencesRepository.setDailyLimitExceededToday(isLimitLock)
        }

        when (lockState) {
            is LockState.CurfewLock -> {
                overlayManager.dismiss()
                com.shieldchild.app.engine.CurfewLockLauncher.launch(this, lockState.reason)
            }
            is LockState.SingleAppLock -> {
                if (fgPkg != packageName) {
                    overlayManager.showAppLock(
                        packageName = lockState.packageName,
                        appName = lockState.appName,
                        limitMinutes = lockState.limitMinutes,
                        usedMinutes = lockState.usedMinutes
                    )
                }
            }
            is LockState.Unlocked -> {
                overlayManager.dismiss()
                com.shieldchild.app.engine.CurfewLockLauncher.dismissLockNotification(this)
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, ShieldApplication.CHANNEL_MONITOR)
            .setContentTitle(str(R.string.shieldchild_protection_active))
            .setContentText(str(R.string.screen_time_and_boundary_limits))
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private var isParentNotificationShown = false

    private fun updateParentSessionNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val active = preferencesRepository.isParentSessionActive()
        if (active && !isParentNotificationShown) {
            val lockIntent = PendingIntent.getService(
                this, NOTIFICATION_PARENT_SESSION_ID,
                Intent(this, ShieldMonitorService::class.java).setAction(ACTION_END_PARENT_SESSION),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            nm.notify(
                NOTIFICATION_PARENT_SESSION_ID,
                NotificationCompat.Builder(this, ShieldApplication.CHANNEL_ALERTS)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setContentTitle(str(R.string.parent_mode))
                    .setContentText(str(R.string.tap_to_lock_the_phone))
                    .setContentIntent(lockIntent)
                    .addAction(0, str(R.string.lock_again), lockIntent)
                    .setOngoing(true)
                    .setSilent(true)
                    .build()
            )
            isParentNotificationShown = true
        } else if (!active && isParentNotificationShown) {
            nm.cancel(NOTIFICATION_PARENT_SESSION_ID)
            isParentNotificationShown = false
        }
    }

    private var warnedGrantEnd = 0L
    private var warnedAtMinutes = Int.MAX_VALUE

    /** One notification at 5 min and one at 1 min before a parent's time grant ends. */
    private fun warnBeforeGrantEnds() {
        val now = System.currentTimeMillis()
        val grantEnd = maxOf(preferencesRepository.getBedtimePausedUntil(), preferencesRepository.getLimitPausedUntil())
        if (grantEnd <= now) return
        if (grantEnd != warnedGrantEnd) {
            warnedGrantEnd = grantEnd
            warnedAtMinutes = Int.MAX_VALUE
        }
        val minutesLeft = ((grantEnd - now + 59_999L) / 60_000L).toInt()
        val threshold = listOf(1, 5).firstOrNull { minutesLeft <= it && it < warnedAtMinutes } ?: return
        warnedAtMinutes = threshold
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            NOTIFICATION_GRANT_WARNING_ID,
            NotificationCompat.Builder(this, ShieldApplication.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(if (threshold == 1) str(R.string.t_1_minute_left) else str(R.string.t_5_minutes_left))
                .setContentText(str(R.string.the_phone_locks_again_when))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (_: Exception) {}
        serviceJob.cancel()
        overlayManager.dismiss()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val POLL_INTERVAL_MS = 500L

        const val ACTION_FOREGROUND_PACKAGE_CHANGED = "com.shieldchild.action.PKG_CHANGED"
        const val ACTION_RECHECK_POLICIES = "com.shieldchild.action.RECHECK"
        const val ACTION_END_PARENT_SESSION = "com.shieldchild.action.END_PARENT_SESSION"
        const val NOTIFICATION_PARENT_SESSION_ID = 1002
        const val NOTIFICATION_GRANT_WARNING_ID = 1003
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        fun start(context: Context) {
            val intent = Intent(context, ShieldMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
