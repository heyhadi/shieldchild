package com.shieldchild.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.shieldchild.app.ShieldApplication
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.engine.CurfewLockLauncher

class ShieldAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        checkAndEnforceCurfew()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val pkgName = event.packageName?.toString() ?: return

            // 1. Instant curfew / limit lockdown enforcement on foreground package switch
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                checkAndEnforceCurfew(pkgName)
            }

            // 2. 24/7 Anti-Uninstall & Anti-Tamper Protection (ACTIVE EVEN DURING ALLOWED USAGE TIME)
            if (!isParentUninstallActive()) {
                val isSettings = pkgName == "com.android.settings" || pkgName.contains(".settings")
                val isInstallerOrStore = pkgName.contains("packageinstaller") ||
                        pkgName.contains("permissioncontroller") ||
                        pkgName.contains("appinstaller") ||
                        pkgName.contains("securitycenter") ||
                        pkgName == "com.android.vending"

                if (isSettings || isInstallerOrStore) {
                    val root = rootInActiveWindow ?: event.source
                    if (root != null) {
                        val matchesShield = root.findAccessibilityNodeInfosByText("ShieldChild")
                        val matchesPkg = root.findAccessibilityNodeInfosByText("com.shieldchild.app")
                        if (!matchesShield.isNullOrEmpty() || !matchesPkg.isNullOrEmpty()) {
                            // Child is attempting to:
                            // - Uninstall ShieldChild via launcher drag or package installer
                            // - Force Stop / Clear Cache / Storage in Settings
                            // - Deactivate Device Admin
                            // - Turn off Accessibility Service in Settings
                            // Immediately deflect to Home and lock device behind Parent Passcode!
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            CurfewLockLauncher.launch(this, LockReason.PARENT_INSTANT_FREEZE)
                            return
                        }
                    }
                }
            }

            // 3. Notify monitoring service of real-time package switch
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val intent = Intent(this, ShieldMonitorService::class.java).apply {
                    action = ShieldMonitorService.ACTION_FOREGROUND_PACKAGE_CHANGED
                    putExtra(ShieldMonitorService.EXTRA_PACKAGE_NAME, pkgName)
                }
                try {
                    startService(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun checkAndEnforceCurfew(foregroundPkg: String? = null) {
        val app = applicationContext as? ShieldApplication ?: return
        val repo = app.preferencesRepository
        if (!repo.isSetupCompleted()) return

        val policy = repo.getCurfewPolicy()
        val isDailyExceeded = repo.isDailyLimitExceededToday()
        val reason = CurfewLockLauncher.evaluateRebootLockReason(
            policy = policy,
            isDailyExceeded = isDailyExceeded,
            isOverrideActive = repo.isCurfewOverrideActive()
        )

        if (reason != null) {
            // Curfew is active: ensure CurfewLockActivity stays on top
            if (foregroundPkg != packageName && foregroundPkg != "com.shieldchild.app.ui.curfew.CurfewLockActivity") {
                CurfewLockLauncher.launch(this, reason)
            }
        }
    }

    override fun onInterrupt() {
        // Accessibility service interrupted
    }

    companion object {
        @Volatile
        private var isParentUninstallAllowed: Boolean = false
        private var parentUninstallAllowedUntil: Long = 0L

        /**
         * Temporarily allows the authenticated parent to uninstall or reconfigure ShieldChild.
         */
        fun allowParentUninstall(durationMs: Long = 60000L) {
            isParentUninstallAllowed = true
            parentUninstallAllowedUntil = System.currentTimeMillis() + durationMs
        }

        fun revokeParentUninstall() {
            isParentUninstallAllowed = false
            parentUninstallAllowedUntil = 0L
        }

        fun isParentUninstallActive(): Boolean {
            if (!isParentUninstallAllowed) return false
            if (System.currentTimeMillis() > parentUninstallAllowedUntil) {
                isParentUninstallAllowed = false
                return false
            }
            return true
        }
    }
}
