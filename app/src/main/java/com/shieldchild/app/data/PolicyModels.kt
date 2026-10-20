package com.shieldchild.app.data

/**
 * Limit and restriction configuration for an individual app.
 */
data class AppLimit(
    val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Long,
    val isBlocked: Boolean = false,
    val isEssential: Boolean = false,
    val allowInBedtime: Boolean = false,
    val usageTodayMinutes: Long = 0L
) {
    val isLimitExceeded: Boolean
        get() = !isEssential && (isBlocked || (dailyLimitMinutes > 0 && usageTodayMinutes >= dailyLimitMinutes))

    val remainingMinutes: Long
        get() = if (isEssential) Long.MAX_VALUE else if (dailyLimitMinutes > usageTodayMinutes) dailyLimitMinutes - usageTodayMinutes else 0L
}

/**
 * Global phone lockdown policy.
 */
data class DeviceCurfewPolicy(
    val isDailyLimitEnabled: Boolean = true,
    val dailyTotalLimitMinutes: Long = 120L, // 2 hours default
    val unlimitedDays: Set<String> = setOf("Sat", "Sun"), // Days where whole day usage is allowed
    val isCurfewScheduled: Boolean = true,
    val curfewStartHour: Int = 21, // 9:00 PM
    val curfewStartMinute: Int = 0,
    val curfewEndHour: Int = 7, // 7:00 AM
    val curfewEndMinute: Int = 0,
    val isInstantLocked: Boolean = false, // One-tap manual lockout
    val bonusMinutesToday: Long = 0L,
    /** The parent's phone, for "Call parent" / "Text parent". Emergencies use the system dialer. */
    val emergencyPhoneNumber: String = ""
) {
    val effectiveDailyLimitMinutes: Long
        get() = dailyTotalLimitMinutes + bonusMinutesToday

    fun isWholeDayAllowed(dayName: String): Boolean {
        return !isDailyLimitEnabled || unlimitedDays.contains(dayName)
    }
}

/**
 * Current runtime lock assessment result.
 */
sealed class LockState {
    object Unlocked : LockState()
    
    data class CurfewLock(
        val reason: LockReason,
        val effectiveLimitMinutes: Long,
        val usedMinutes: Long
    ) : LockState()

    data class SingleAppLock(
        val packageName: String,
        val appName: String,
        val limitMinutes: Long,
        val usedMinutes: Long,
        /** Blocked by the parent rather than out of time: extra time can't open it. */
        val isBlocked: Boolean = false
    ) : LockState()
}

enum class LockReason {
    TOTAL_SCREEN_TIME_EXCEEDED,
    SCHEDULED_BEDTIME_CURFEW,
    PARENT_INSTANT_FREEZE
}

/**
 * Temporary exceptions a parent has granted. Each one lifts only its own kind of lock;
 * a parent session lifts every lock (the parent is using the phone) until it ends.
 */
data class AccessState(
    val isBedtimePaused: Boolean = false,
    val isLimitPaused: Boolean = false,
    /** Extra minutes given during an instant lock; the instant lock returns when they run out. */
    val isInstantPaused: Boolean = false,
    val isParentSession: Boolean = false,
    val pausedApps: Set<String> = emptySet()
)

enum class GrantMethod(@androidx.annotation.StringRes val labelRes: Int) {
    PIN(com.shieldchild.app.R.string.method_pin),
    FINGERPRINT(com.shieldchild.app.R.string.method_fingerprint),
    RECOVERY_KEY(com.shieldchild.app.R.string.method_recovery_key),
    REMOTE_CODE(com.shieldchild.app.R.string.method_remote_code),
    /** Events ShieldChild records itself, e.g. a clock change. */
    SYSTEM(com.shieldchild.app.R.string.method_system)
}

data class GrantLogEntry(
    val timestamp: Long,
    val method: GrantMethod,
    /** A [GrantLog] code, turned into text in the current language when shown. */
    val description: String
)

/** Language-neutral codes stored in the grant log. */
object GrantLog {
    const val PARENT_ACCESS = "PARENT_ACCESS"
    const val REST_OF_TODAY = "REST_OF_TODAY"
    const val BEDTIME_OFF = "BEDTIME_OFF"
    const val INSTANT_OFF = "INSTANT_OFF"
    const val CLOCK_CHANGED = "CLOCK_CHANGED"
    fun minutes(minutes: Long) = "MINUTES:$minutes"
}
