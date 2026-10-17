package com.shieldchild.app.engine

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/**
 * Calls always win over the lock: a locked child must be able to make an emergency call,
 * call a parent and answer an incoming call. The lock steps aside while the phone app is in
 * front or a call is active, and comes back afterwards.
 */
object PhoneGuard {
    private val STRICT_INCALL_PACKAGES = setOf(
        "com.android.phone",            // emergency dialer
        "com.android.server.telecom",
        "com.android.incallui",
        "com.samsung.android.incallui"
    )

    private val FULL_DIALER_PACKAGES = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer"
    )

    private val WHATSAPP_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b"
    )

    val ALLOWED_SYSTEM_HELPERS = setOf(
        "com.android.systemui",
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.google.android.providers.media.module",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "android",
        "com.google.android.gms"
    )

    fun isInputMethod(context: Context, packageName: String?): Boolean {
        if (packageName == null) return false
        if (packageName.contains("inputmethod", ignoreCase = true) ||
            packageName.contains("keyboard", ignoreCase = true) ||
            packageName.contains(".ime", ignoreCase = true) ||
            packageName.contains("swiftkey", ignoreCase = true)
        ) {
            return true
        }
        return try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.inputMethodList?.any { it.packageName == packageName } == true
        } catch (_: Exception) {
            false
        }
    }

    fun isSystemHelper(packageName: String?): Boolean {
        return packageName != null && packageName in ALLOWED_SYSTEM_HELPERS
    }

    @Volatile
    private var activeWhitelistedPackage: String? = null

    fun startWhitelistedSession(packageName: String) {
        activeWhitelistedPackage = packageName
        allowExternalCommunicationWindow(10_000L)
    }

    fun clearWhitelistedSession() {
        activeWhitelistedPackage = null
    }

    fun getActiveWhitelistedPackage(): String? = activeWhitelistedPackage

    fun isWhitelistedSessionActiveFor(packageName: String?): Boolean {
        val active = activeWhitelistedPackage ?: return false
        return packageName == null || packageName == active
    }

    fun resolveInstalledWhatsAppPackage(context: Context): String? {
        val pm = context.packageManager
        return WHATSAPP_PACKAGES.firstOrNull { pkg ->
            runCatching { pm.getPackageInfo(pkg, 0) != null }.getOrDefault(false)
        }
    }

    fun isAllowedVoipCall(context: Context, foregroundPackage: String?): Boolean {
        if (foregroundPackage == null) return false
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val isVoipAudio = audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
                audio.mode == AudioManager.MODE_RINGTONE
        if (!isVoipAudio) return false
        if (foregroundPackage in WHATSAPP_PACKAGES) return true
        val repo = (context.applicationContext as? com.shieldchild.app.ShieldApplication)?.preferencesRepository
        return repo?.getAppLimits()?.get(foregroundPackage)?.isEssential == true
    }

    @Volatile
    private var externalActionGraceUntilUptimeMs: Long = 0L

    fun allowExternalCommunicationWindow(durationMs: Long = 30_000L) {
        externalActionGraceUntilUptimeMs = SystemClock.elapsedRealtime() + durationMs
    }

    fun isWithinCommunicationWindow(): Boolean =
        SystemClock.elapsedRealtime() < externalActionGraceUntilUptimeMs

    internal fun resetForTest() {
        externalActionGraceUntilUptimeMs = 0L
        activeWhitelistedPackage = null
    }

    fun isInCall(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL ||
                audio.mode == AudioManager.MODE_RINGTONE
    }

    fun isPhonePackage(context: Context, packageName: String?): Boolean {
        if (packageName == null) return false
        if (packageName in STRICT_INCALL_PACKAGES) return true
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val isFullDialer = packageName in FULL_DIALER_PACKAGES || packageName == telecom?.defaultDialerPackage
        if (isFullDialer) {
            return isInCall(context) || isWithinCommunicationWindow()
        }
        return false
    }

    /** True while the lock must not cover the screen. */
    fun shouldYield(context: Context, foregroundPackage: String?): Boolean {
        if (isPhonePackage(context, foregroundPackage)) return true
        if (isInCall(context) && (foregroundPackage == null || isPhonePackage(context, foregroundPackage))) {
            return true
        }
        if (isAllowedVoipCall(context, foregroundPackage)) return true
        if (isWhitelistedSessionActiveFor(foregroundPackage)) return true
        val activeWhitelisted = activeWhitelistedPackage
        if (activeWhitelisted != null) {
            if (isInputMethod(context, foregroundPackage) || isSystemHelper(foregroundPackage)) {
                return true
            }
        }
        return false
    }
}
