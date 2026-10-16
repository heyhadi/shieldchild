package com.shieldchild.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.shieldchild.app.ShieldApplication
import com.shieldchild.app.data.PreferencesRepository
import com.shieldchild.app.engine.CurfewLockLauncher

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == "android.intent.action.REBOOT" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val app = context.applicationContext as? ShieldApplication
            val repo = app?.preferencesRepository ?: PreferencesRepository(context)

            // Only enforce policies if parent has completed setup
            if (!repo.isSetupCompleted()) return

            // 1. Resurrect background monitoring loop
            try {
                ShieldMonitorService.start(context)
            } catch (_: Exception) {}

            // 2. Immediately evaluate if the device should be locked upon reboot
            val policy = repo.getCurfewPolicy()
            val isDailyExceeded = repo.isDailyLimitExceededToday()
            val reason = CurfewLockLauncher.evaluateRebootLockReason(
                policy = policy,
                isDailyExceeded = isDailyExceeded,
                isOverrideActive = repo.isCurfewOverrideActive()
            )

            if (reason != null) {
                // Immediately force curfew lock activity on screen!
                CurfewLockLauncher.launch(context, reason)
            }
        }
    }
}
