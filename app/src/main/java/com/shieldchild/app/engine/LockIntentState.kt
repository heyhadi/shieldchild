package com.shieldchild.app.engine

import com.shieldchild.app.data.LockReason

/** What the lock screen is locking: one app ([lockedPackage]) or the whole phone. */
data class LockIntentState(
    val lockedPackage: String?,
    val lockedAppName: String?,
    val reason: LockReason
) {
    companion object {
        /**
         * Reads a launch intent's extras. The lock screen relaunches itself with no extras whenever
         * a dialog takes focus; that must keep [current], or a grant would lift the wrong lock.
         */
        fun from(current: LockIntentState?, lockedPackage: String?, lockedAppName: String?, reasonName: String?): LockIntentState {
            if (current != null && lockedPackage == null && reasonName == null) return current
            val reason = reasonName?.let { name -> LockReason.entries.firstOrNull { it.name == name } }
                ?: LockReason.TOTAL_SCREEN_TIME_EXCEEDED
            return LockIntentState(lockedPackage, lockedAppName, reason)
        }
    }
}

object ForegroundTracker {
    /**
     * The package to report as in front for a window event, or null to keep the previous one.
     * The app-limit overlay is a window of this app: reporting it would unlock the app it covers.
     */
    fun reportedPackage(eventPackage: String, ownPackage: String, isAppLockOverlayShowing: Boolean): String? =
        if (eventPackage == ownPackage && isAppLockOverlayShowing) null else eventPackage
}
