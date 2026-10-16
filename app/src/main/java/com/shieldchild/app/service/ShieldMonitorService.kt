package com.shieldchild.app.service

import android.app.Notification
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
        startMonitoringLoop()
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
        val policy = preferencesRepository.getCurfewPolicy()
        val appLimits = preferencesRepository.getAppLimits()
        val fgPkg = currentForegroundPackage ?: usageMonitor.getForegroundPackageName()
        val usage = usageMonitor.queryDailyUsage()

        val lockState = policyEngine.evaluateLockState(
            policy = policy,
            appLimits = appLimits,
            usageSummary = usage,
            foregroundPackage = fgPkg,
            isCurfewOverrideActive = preferencesRepository.isCurfewOverrideActive()
        )

        when (lockState) {
            is LockState.CurfewLock -> {
                overlayManager.dismiss()
                preferencesRepository.setDailyLimitExceededToday(lockState.reason == LockReason.TOTAL_SCREEN_TIME_EXCEEDED)
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
            .setContentTitle("ShieldChild Protection Active")
            .setContentText("Screen time and boundary limits are being actively protected.")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        overlayManager.dismiss()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val POLL_INTERVAL_MS = 500L

        const val ACTION_FOREGROUND_PACKAGE_CHANGED = "com.shieldchild.action.PKG_CHANGED"
        const val ACTION_RECHECK_POLICIES = "com.shieldchild.action.RECHECK"
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
