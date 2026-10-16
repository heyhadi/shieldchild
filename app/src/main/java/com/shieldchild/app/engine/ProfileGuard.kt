package com.shieldchild.app.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.UserManager

/**
 * Ensures ShieldChild only enforces locks, overlays, notifications, and accessibility
 * deflections when the Android user profile hosting this app instance is in the foreground.
 */
object ProfileGuard {
    @Volatile
    private var foregroundFlag: Boolean = true

    fun onUserForeground() {
        foregroundFlag = true
    }

    fun onUserBackground() {
        foregroundFlag = false
    }

    internal fun resetForTest(isForeground: Boolean = true) {
        foregroundFlag = isForeground
    }

    /**
     * Returns true ONLY when the user profile hosting this app instance
     * is the active foreground user on the device.
     */
    fun isCurrentProfileActive(context: Context): Boolean {
        if (!foregroundFlag) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            if (um != null) {
                return um.isUserForeground
            }
        }
        return try {
            val myUserId = Process.myUserHandle().hashCode()
            val getCurrentUser = ActivityManager::class.java.getDeclaredMethod("getCurrentUser")
            val currentUserId = getCurrentUser.invoke(null) as? Int
            if (currentUserId != null) currentUserId == myUserId else foregroundFlag
        } catch (_: Throwable) {
            foregroundFlag
        }
    }

    /**
     * Returns true ONLY when the physical screen is interactive (awake)
     * AND this user profile is currently in the foreground.
     */
    fun isScreenInteractiveForProfile(context: Context): Boolean {
        if (!isCurrentProfileActive(context)) return false
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive == true
    }
}
