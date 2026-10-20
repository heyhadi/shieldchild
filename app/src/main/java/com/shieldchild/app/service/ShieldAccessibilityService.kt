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
        instance = this
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
                val eventIsPip = try {
                    windows?.firstOrNull { it.id == event.windowId }?.isInPictureInPictureMode == true
                } catch (_: Exception) { false }
                if (eventIsPip) {
                    // A PiP window's event doesn't bring its app to the front; reporting it would
                    // put the app's lock over Home. Over another app, that app stays in front.
                    val launcher = try {
                        com.shieldchild.app.engine.HomeWindows.launcherUnderPip(screenWindows().map { it.first }, homeLaunchers)
                    } catch (_: Exception) { null } ?: return
                    reportedPackage = try { handleWindowsBesideHome(launcher, repo) } catch (_: Exception) { launcher }
                } else if (pkgName in homeLaunchers) {
                    try {
                        reportedPackage = handleWindowsBesideHome(pkgName, repo)
                    } catch (_: Exception) {}
                }

                val foreground = com.shieldchild.app.engine.ForegroundTracker.reportedPackage(
                    reportedPackage, packageName, com.shieldchild.app.overlay.AppShieldOverlayManager.isShowing
                ) ?: return
                val intent = Intent(this, ShieldMonitorService::class.java).apply {
                    action = ShieldMonitorService.ACTION_FOREGROUND_PACKAGE_CHANGED
                    putExtra(ShieldMonitorService.EXTRA_PACKAGE_NAME, foreground)
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

        val reason = com.shieldchild.app.engine.LockDecider.deviceLock(
            com.shieldchild.app.engine.LockDecider.snapshot(this, repo)
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

    /** App windows on screen, top first. */
    private fun screenWindows(): List<Pair<com.shieldchild.app.engine.ScreenWindow, android.view.accessibility.AccessibilityWindowInfo>> =
        (windows ?: emptyList()).filter {
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
        }.mapNotNull { win ->
            val pkg = win.root?.packageName?.toString() ?: return@mapNotNull null
            com.shieldchild.app.engine.ScreenWindow(pkg, win.isInPictureInPictureMode) to win
        }

    /**
     * Called by the monitor service on its checks. Home brings no new window event when a lock
     * ends while it's in front (a parent session running out), so a locked app's PiP is closed here.
     */
    private fun recheckBesideHome(foregroundPkg: String) {
        if (foregroundPkg !in getHomeLauncherPackages()) return
        if (!com.shieldchild.app.engine.ProfileGuard.isScreenInteractiveForProfile(this)) return
        try {
            handleWindowsBesideHome(foregroundPkg, ShieldApplication.instance.preferencesRepository)
        } catch (_: Exception) {}
    }

    /** Returns the app to report while Home has focus, closing a locked app's picture-in-picture. */
    private fun handleWindowsBesideHome(launcherPkg: String, repo: com.shieldchild.app.data.PreferencesRepository): String {
        val seen = screenWindows()
        val snapshot = com.shieldchild.app.engine.LockDecider.snapshot(this, repo)
        val decision = com.shieldchild.app.engine.HomeWindows.decide(
            launcherPackage = launcherPkg,
            windows = seen.map { it.first },
            limitedApps = snapshot.appLimits.keys,
            isLocked = { pkg -> com.shieldchild.app.engine.LockDecider.isAppLocked(snapshot, pkg) }
        )
        decision.closePipPackage?.let { pkg ->
            seen.firstOrNull { it.first.packageName == pkg && it.first.isPictureInPicture }?.second?.let { closePictureInPicture(it) }
        }
        return decision.reportedPackage
    }

    /** Stops playback, then closes the window with the PiP "dismiss" action System UI provides. */
    private fun closePictureInPicture(win: android.view.accessibility.AccessibilityWindowInfo) {
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val focus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN).build()
        audio.requestAudioFocus(focus)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ audio.abandonAudioFocusRequest(focus) }, 1_000L)
        win.root?.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id)
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

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: ShieldAccessibilityService? = null

        /** See [recheckBesideHome]. Window queries run on the service's main thread. */
        fun recheckBesideHome(foregroundPkg: String) {
            val service = instance ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post { service.recheckBesideHome(foregroundPkg) }
        }

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
