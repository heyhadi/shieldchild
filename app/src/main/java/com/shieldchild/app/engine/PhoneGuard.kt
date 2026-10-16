package com.shieldchild.app.engine

import android.content.Context
import android.media.AudioManager
import android.telecom.TelecomManager

/**
 * Calls always win over the lock: a locked child must be able to make an emergency call,
 * call a parent and answer an incoming call. The lock steps aside while the phone app is in
 * front or a call is active, and comes back afterwards.
 */
object PhoneGuard {
    private val KNOWN_PHONE_PACKAGES = setOf(
        "com.android.phone",            // emergency dialer
        "com.android.server.telecom",
        "com.android.incallui",
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui"
    )

    fun isInCall(context: Context): Boolean {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return audio.mode == AudioManager.MODE_IN_CALL ||
                audio.mode == AudioManager.MODE_IN_COMMUNICATION ||
                audio.mode == AudioManager.MODE_RINGTONE
    }

    fun isPhonePackage(context: Context, packageName: String?): Boolean {
        if (packageName == null) return false
        if (packageName in KNOWN_PHONE_PACKAGES) return true
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        return packageName == telecom?.defaultDialerPackage
    }

    /** True while the lock must not cover the screen. */
    fun shouldYield(context: Context, foregroundPackage: String?): Boolean =
        isInCall(context) || isPhonePackage(context, foregroundPackage)
}
