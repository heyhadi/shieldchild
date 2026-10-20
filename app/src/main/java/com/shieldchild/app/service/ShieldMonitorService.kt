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
import com.shieldchild.app.engine.LockDecider
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
    // One evaluation at a time: the loop, app switches and receivers all touch the same state.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val serviceScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + serviceJob)

    // WindowManager views (the app-limit overlay) must be added and removed on the main thread.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun onMain(block: () -> Unit) = mainHandler.post(block)

    private var lastWallMs = 0L
    private var lastUptimeMs = 0L

    private lateinit var preferencesRepository: PreferencesRepository
    private lateinit var usageMonitor: UsageMonitor
    private lateinit var overlayManager: AppShieldOverlayManager

    private var currentForegroundPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        preferencesRepository = ShieldApplication.instance.preferencesRepository
        usageMonitor = UsageMonitor(this)
        overlayManager = AppShieldOverlayManager(this)

        startForeground(NOTIFICATION_ID, buildForegroundNotification())
        // Device Owner set up with adb (see README) never gets the provisioning callback, so the
        // hardened restrictions are applied here; it does nothing on a normal install.
        try {
            ShieldDeviceAdminReceiver.applyDeviceOwnerRestrictions(this)
        } catch (_: Exception) {}
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        registerReceiver(screenOnReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        registerReceiver(clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerReceiver(userSwitchReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_BACKGROUND)
            addAction(Intent.ACTION_USER_FOREGROUND)
        })
        if (preferencesRepository.getCurfewPolicy().isInstantLocked) {
            com.shieldchild.app.engine.PhoneGuard.clearWhitelistedSession()
        }
        startMonitoringLoop()
    }

    private val userSwitchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_USER_BACKGROUND -> {
                    com.shieldchild.app.engine.ProfileGuard.onUserBackground()
                    currentForegroundPackage = null
                    onMain { overlayManager.dismiss() }
                    com.shieldchild.app.engine.CurfewLockLauncher.dismissLockNotification(this@ShieldMonitorService)
                    com.shieldchild.app.ui.curfew.CurfewLockActivity.dismissIfShowing()
                }
                Intent.ACTION_USER_FOREGROUND -> {
                    com.shieldchild.app.engine.ProfileGuard.onUserForeground()
                    serviceScope.launch { evaluateCurrentState() }
                }
            }
        }
    }

    /** Handing the phone back usually means pressing power: end a parent session then. */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            ShieldAccessibilityService.revokeParentUninstall()
            com.shieldchild.app.engine.PhoneGuard.clearWhitelistedSession()
            if (!com.shieldchild.app.engine.ProfileGuard.isCurrentProfileActive(this@ShieldMonitorService)) return
            if (!preferencesRepository.hasParentSessionRecord()) return
            // The screen also goes off at the ear during a call; keep the parent's session.
            val audio = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val inCall = audio?.mode == AudioManager.MODE_IN_CALL
            if (inCall) return
            preferencesRepository.endParentSession()
            // Do NOT call evaluateCurrentState() while screen is off; screenOnReceiver evaluates on wake.
        }
    }

    /** Checking right when the screen comes on replaces fast polling while it's off. */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!com.shieldchild.app.engine.ProfileGuard.isCurrentProfileActive(this@ShieldMonitorService)) return
            serviceScope.launch { evaluateCurrentState() }
        }
    }

    /** A manual clock or time-zone change (SEC-4) locks the phone until a parent unlocks it. */
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!com.shieldchild.app.engine.ProfileGuard.isCurrentProfileActive(this@ShieldMonitorService)) return
            serviceScope.launch {
                val resolver = contentResolver
                val autoTime = android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.AUTO_TIME, 1) == 1
                val autoZone = android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.AUTO_TIME_ZONE, 1) == 1
                val parentIsHere = preferencesRepository.isParentSessionActive() ||
                        com.shieldchild.app.ui.dashboard.ParentDashboardActivity.isParentAuthenticatedState.value

                val (anchorWallMs, anchorUptimeMs, anchorBoot) = preferencesRepository.getClockAnchor()
                val nowWallMs = System.currentTimeMillis()
                val nowUptimeMs = android.os.SystemClock.elapsedRealtime()
                val currentBoot = preferencesRepository.getBootCount()

                val tampered = when (intent.action) {
                    Intent.ACTION_TIMEZONE_CHANGED -> !autoZone
                    else -> {
                        if (currentBoot == anchorBoot && anchorWallMs != 0L) {
                            com.shieldchild.app.engine.ClockGuard.isSuspiciousJump(
                                anchorWallMs, anchorUptimeMs, nowWallMs, nowUptimeMs, autoTime
                            )
                        } else {
                            false
                        }
                    }
                }
                if (tampered && !parentIsHere && preferencesRepository.isSetupCompleted()) {
                    preferencesRepository.setInstantLock(true)
                    preferencesRepository.addGrantLog(com.shieldchild.app.data.GrantMethod.SYSTEM, com.shieldchild.app.data.GrantLog.CLOCK_CHANGED)
                    preferencesRepository.saveClockAnchor(nowWallMs, nowUptimeMs, currentBoot)
                } else if (parentIsHere || (autoTime && autoZone) || currentBoot != anchorBoot || anchorWallMs == 0L) {
                    preferencesRepository.saveClockAnchor(nowWallMs, nowUptimeMs, currentBoot)
                }
                evaluateCurrentState()
            }
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
                ShieldAccessibilityService.revokeParentUninstall()
                preferencesRepository.endParentSession()
                serviceScope.launch { evaluateCurrentState() }
            }
        }
        return START_STICKY
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            while (isActive) {
                if (com.shieldchild.app.engine.ProfileGuard.isScreenInteractiveForProfile(this@ShieldMonitorService)) {
                    evaluateCurrentState()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun evaluateCurrentState() {
        if (!com.shieldchild.app.engine.ProfileGuard.isScreenInteractiveForProfile(this)) {
            if (!com.shieldchild.app.engine.ProfileGuard.isCurrentProfileActive(this)) {
                onMain { overlayManager.dismiss() }
                com.shieldchild.app.engine.CurfewLockLauncher.dismissLockNotification(this)
                com.shieldchild.app.ui.curfew.CurfewLockActivity.dismissIfShowing()
            }
            return
        }
        val nowWallMs = System.currentTimeMillis()
        val nowUptimeMs = android.os.SystemClock.elapsedRealtime()
        val currentBoot = preferencesRepository.getBootCount()
        val (anchorWallMs, anchorUptimeMs, anchorBoot) = preferencesRepository.getClockAnchor()

        val resolver = contentResolver
        val autoTime = try {
            android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.AUTO_TIME, 1) == 1
        } catch (_: Exception) { true }
        val autoZone = try {
            android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.AUTO_TIME_ZONE, 1) == 1
        } catch (_: Exception) { true }

        val parentIsHere = preferencesRepository.isParentSessionActive() ||
                com.shieldchild.app.ui.dashboard.ParentDashboardActivity.isParentAuthenticatedState.value

        if (currentBoot != anchorBoot || anchorWallMs == 0L) {
            preferencesRepository.saveClockAnchor(nowWallMs, nowUptimeMs, currentBoot)
        } else if (parentIsHere || (autoTime && autoZone)) {
            preferencesRepository.saveClockAnchor(nowWallMs, nowUptimeMs, currentBoot)
        } else if (!autoTime && preferencesRepository.isSetupCompleted()) {
            if (com.shieldchild.app.engine.ClockGuard.isSuspiciousJump(
                    anchorWallMs, anchorUptimeMs, nowWallMs, nowUptimeMs, autoTime
                )
            ) {
                preferencesRepository.setInstantLock(true)
                preferencesRepository.addGrantLog(
                    com.shieldchild.app.data.GrantMethod.SYSTEM,
                    com.shieldchild.app.data.GrantLog.CLOCK_CHANGED
                )
                preferencesRepository.saveClockAnchor(nowWallMs, nowUptimeMs, currentBoot)
            }
        }

        // Idle or 30-minute limit reached: close the session so its time is excluded and the lock returns.
        if (preferencesRepository.hasParentSessionRecord() && !preferencesRepository.isParentSessionActive()) {
            preferencesRepository.endParentSession()
        }
        updateParentSessionNotification()
        warnBeforeGrantEnds()

        val fgPkg = currentForegroundPackage ?: usageMonitor.getForegroundPackageName()
        val now = System.currentTimeMillis()
        // Measured live here and shared, so every other part of the app decides with the same usage.
        val usage = LockDecider.refreshUsage(preferencesRepository, now) { usageMonitor.queryDailyUsage() }
        val lockState = LockDecider.decide(LockDecider.snapshot(preferencesRepository, usage, fgPkg, now))

        // Parent grants read this to know whether the daily limit is what they're lifting.
        val isLimitLock = lockState is LockState.CurfewLock && lockState.reason == LockReason.TOTAL_SCREEN_TIME_EXCEEDED
        if (isLimitLock != preferencesRepository.isDailyLimitExceededToday()) {
            preferencesRepository.setDailyLimitExceededToday(isLimitLock)
        }

        when (lockState) {
            is LockState.CurfewLock -> {
                onMain { overlayManager.dismiss() }
                // Don't cover a parent working in the dashboard (UI-17) or a phone call (BUG-4),
                // and don't relaunch a lock screen that is already showing (BUG-7).
                val parentInDashboard = fgPkg == packageName &&
                        com.shieldchild.app.ui.dashboard.ParentDashboardActivity.isParentAuthenticatedState.value
                if (!CurfewLockActivity.isShowing && !parentInDashboard &&
                    !com.shieldchild.app.engine.PhoneGuard.shouldYield(this, fgPkg)
                ) {
                    com.shieldchild.app.engine.CurfewLockLauncher.launch(this, lockState.reason)
                }
            }
            is LockState.SingleAppLock -> {
                if (fgPkg != packageName) onMain {
                    overlayManager.showAppLock(
                        packageName = lockState.packageName,
                        appName = lockState.appName,
                        limitMinutes = lockState.limitMinutes,
                        usedMinutes = lockState.usedMinutes,
                        isBlocked = lockState.isBlocked
                    )
                }
            }
            is LockState.Unlocked -> {
                onMain { overlayManager.dismiss() }
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
        try {
            unregisterReceiver(screenOnReceiver)
            unregisterReceiver(clockReceiver)
            unregisterReceiver(userSwitchReceiver)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        // App switches are handled instantly by the accessibility service; this only catches limits
        // running out while staying in one app.
        const val POLL_INTERVAL_MS = 5_000L

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
