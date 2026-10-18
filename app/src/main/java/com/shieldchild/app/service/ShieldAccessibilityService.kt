package com.shieldchild.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.shieldchild.app.ShieldApplication
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.engine.CurfewLockLauncher
import com.shieldchild.app.ui.curfew.CurfewLockActivity

class ShieldAccessibilityService : AccessibilityService() {

    private var lastShadeDismissUptimeMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        checkAndEnforceCurfew()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!com.shieldchild.app.engine.ProfileGuard.isScreenInteractiveForProfile(this)) return

        // Touch-driven events keep a parent session alive (it ends after 5 min idle).
        // Content changes are excluded: a playing video would otherwise keep it open forever.
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            ShieldApplication.instance.preferencesRepository.touchParentSession()
        }

        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val pkgName = event.packageName?.toString() ?: return

            // 0. Notification shade collapse when CurfewLockActivity is showing (Task 8)
            val nowUptime = android.os.SystemClock.elapsedRealtime()
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                CurfewLockActivity.isShowing &&
                pkgName == "com.android.systemui" &&
                nowUptime - lastShadeDismissUptimeMs > 1_000L &&
                !com.shieldchild.app.engine.PhoneGuard.isInCall(this) &&
                !com.shieldchild.app.engine.PhoneGuard.isAllowedVoipCall(this, "com.whatsapp")
            ) {
                lastShadeDismissUptimeMs = nowUptime
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                } else {
                    @Suppress("DEPRECATION")
                    sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
                }
            }

            val activeWhitelisted = com.shieldchild.app.engine.PhoneGuard.getActiveWhitelistedPackage()
            val isHelperOrIme = com.shieldchild.app.engine.PhoneGuard.isInputMethod(this, pkgName) ||
                    com.shieldchild.app.engine.PhoneGuard.isSystemHelper(pkgName)

            if (activeWhitelisted != null && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (pkgName != activeWhitelisted &&
                    pkgName != packageName &&
                    !isHelperOrIme
                ) {
                    com.shieldchild.app.engine.PhoneGuard.clearWhitelistedSession()
                } else if (pkgName == activeWhitelisted) {
                    val cls = event.className?.toString().orEmpty()
                    if (cls.contains("WebView", ignoreCase = true) || cls.contains("InAppBrowser", ignoreCase = true)) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        return
                    }
                }
            }

            // 1. Instant curfew / limit lockdown enforcement on foreground package switch
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                checkAndEnforceCurfew(pkgName)
            }

            // 2. 24/7 Anti-Uninstall & Anti-Tamper Protection (Task 3)
            val repo = ShieldApplication.instance.preferencesRepository
            if (shouldGuardSettings(repo.isSetupCompleted())) {
                val isParentActive = repo.isParentSessionActive() ||
                        com.shieldchild.app.ui.dashboard.ParentDashboardActivity.isParentAuthenticatedState.value

                val isSettings = pkgName == "com.android.settings" || pkgName.contains(".settings")
                val isInstallerOrStore = pkgName.contains("packageinstaller") ||
                        pkgName.contains("permissioncontroller") ||
                        pkgName.contains("appinstaller") ||
                        pkgName.contains("securitycenter") ||
                        pkgName == "com.android.vending"

                // A. Always Guard ShieldChild Direct Tampering (Even in Parent Session unless allowParentUninstall is active)
                if (isSettings || isInstallerOrStore) {
                    val root = rootInActiveWindow ?: event.source
                    if (root != null) {
                        val matchesShield = root.findAccessibilityNodeInfosByText("ShieldChild")
                        val matchesPkg = root.findAccessibilityNodeInfosByText("com.shieldchild.app")
                        if (!matchesShield.isNullOrEmpty() || !matchesPkg.isNullOrEmpty()) {
                            deflectAndLock()
                            return
                        }
                    }
                }

                // B. Guard High-Risk System Settings ONLY When Parent Is NOT Active
                if (!isParentActive && isSettings) {
                    val cls = event.className?.toString().orEmpty()
                    val highRiskClasses = listOf(
                        "DevelopmentSettings", "WirelessDebugging", "DateTimeSettings",
                        "UserSettings", "DeviceAdminAdd", "DeviceAdminSettings",
                        "AccessibilitySettings", "UsageAccessDetails", "DrawOverlayDetails",
                        "AppInfoDashboard", "SpaActivity"
                    )
                    val classMatch = highRiskClasses.any { cls.contains(it, ignoreCase = true) }
                    if (classMatch) {
                        deflectAndLock()
                        return
                    }

                    val root = rootInActiveWindow ?: event.source
                    if (root != null) {
                        val highRiskTexts = listOf(
                            "Developer options", "Opsi pengembang",
                            "USB debugging", "Wireless debugging",
                            "Multiple users", "Banyak pengguna",
                            "Device admin apps", "Aplikasi admin perangkat"
                        )
                        val textMatch = highRiskTexts.any {
                            !root.findAccessibilityNodeInfosByText(it).isNullOrEmpty()
                        }
                        if (textMatch) {
                            deflectAndLock()
                            return
                        }
                    }
                }

                // C. Guard Home Launcher Long-Press Popup ONLY on Resolved Launcher Packages
                val homeLaunchers = getHomeLauncherPackages()
                if (!isParentActive && pkgName in homeLaunchers) {
                    val root = rootInActiveWindow ?: event.source
                    if (root != null) {
                        val matchesShield = !root.findAccessibilityNodeInfosByText("ShieldChild").isNullOrEmpty()
                        if (matchesShield) {
                            val popupDangerTexts = listOf(
                                "App info", "Info aplikasi", "Uninstall", "Hapus", "Copot pemasangan"
                            )
                            val hasDangerText = popupDangerTexts.any {
                                !root.findAccessibilityNodeInfosByText(it).isNullOrEmpty()
                            }
                            if (hasDangerText) {
                                performGlobalAction(GLOBAL_ACTION_HOME)
                                return
                            }
                        }
                    }
                }
            }

            // 3. Notify monitoring service of real-time package switch and PiP / Multi-window detection (Task 8)
            if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                var reportedPackage = if (activeWhitelisted != null && isHelperOrIme) {
                    activeWhitelisted
                } else {
                    pkgName
                }
                val homeLaunchers = getHomeLauncherPackages()
                if (pkgName in homeLaunchers && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    try {
                        val activeWindows = windows
                        if (activeWindows != null) {
                            val appLimits = repo.getAppLimits()
                            for (win in activeWindows) {
                                if (win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) {
                                    val winPkg = win.root?.packageName?.toString()
                                    if (winPkg != null && winPkg != pkgName && appLimits.containsKey(winPkg)) {
                                        reportedPackage = winPkg
                                        break
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }

                val intent = Intent(this, ShieldMonitorService::class.java).apply {
                    action = ShieldMonitorService.ACTION_FOREGROUND_PACKAGE_CHANGED
                    putExtra(ShieldMonitorService.EXTRA_PACKAGE_NAME, reportedPackage)
                }
                try {
                    startService(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun checkAndEnforceCurfew(foregroundPkg: String? = null) {
        if (!com.shieldchild.app.engine.ProfileGuard.isScreenInteractiveForProfile(this)) return
        val app = applicationContext as? ShieldApplication ?: return
        val repo = app.preferencesRepository
        if (!repo.isSetupCompleted()) return

        val policy = repo.getCurfewPolicy()
        val isDailyExceeded = repo.isDailyLimitExceededToday()
        val reason = CurfewLockLauncher.evaluateRebootLockReason(
            policy = policy,
            isDailyExceeded = isDailyExceeded,
            access = repo.getAccessState()
        )

        // Calls always win: never cover the dialer, an incoming call or the in-call screen.
        if (com.shieldchild.app.engine.PhoneGuard.shouldYield(this, foregroundPkg)) return

        if (reason != null) {
            // Curfew is active: ensure CurfewLockActivity stays on top
            if (CurfewLockActivity.isShowing) return
            if (foregroundPkg != packageName &&
                foregroundPkg != "com.shieldchild.app.ui.curfew.CurfewLockActivity" &&
                foregroundPkg != "com.android.systemui"
            ) {
                CurfewLockLauncher.launch(this, reason)
            }
        }
    }

    private fun getHomeLauncherPackages(): Set<String> {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            resolveInfos.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Home is handled asynchronously; launching the lock at the same moment let Home land on top
     * of it, so the child was only bounced to the launcher. Show the lock once Home has settled.
     * Instant lock is saved because the lock screen closes itself when no saved rule applies.
     */
    private fun deflectAndLock() {
        ShieldApplication.instance.preferencesRepository.setInstantLock(true)
        performGlobalAction(GLOBAL_ACTION_HOME)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            CurfewLockLauncher.launch(this, LockReason.PARENT_INSTANT_FREEZE)
        }, 400L)
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

        /**
         * Before setup there is no PIN to unlock with, and setup itself sends the parent to
         * Settings screens that show "ShieldChild", so deflecting then would lock the phone for good.
         */
        fun shouldGuardSettings(isSetupCompleted: Boolean): Boolean =
            isSetupCompleted && !isParentUninstallActive()

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
