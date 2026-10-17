package com.shieldchild.app.engine

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.telecom.TelecomManager

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

    @Volatile
    private var externalActionGraceUntilUptimeMs: Long = 0L

    fun allowExternalCommunicationWindow(durationMs: Long = 30_000L) {
        externalActionGraceUntilUptimeMs = SystemClock.elapsedRealtime() + durationMs
    }

    fun isWithinCommunicationWindow(): Boolean =
        SystemClock.elapsedRealtime() < externalActionGraceUntilUptimeMs

    internal fun resetForTest() {
        externalActionGraceUntilUptimeMs = 0L
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
        return false
    }
}
