package com.shieldchild.app.data

/**
 * Limit and restriction configuration for an individual app.
 */
data class AppLimit(
    val packageName: String,
    val appName: String,
    val dailyLimitMinutes: Long,
    val isBlocked: Boolean = false,
    val usageTodayMinutes: Long = 0L
) {
    val isLimitExceeded: Boolean
        get() = isBlocked || (dailyLimitMinutes > 0 && usageTodayMinutes >= dailyLimitMinutes)

    val remainingMinutes: Long
        get() = if (dailyLimitMinutes > usageTodayMinutes) dailyLimitMinutes - usageTodayMinutes else 0L
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
    val emergencyPhoneNumber: String = "911"
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
        val usedMinutes: Long
    ) : LockState()
}

enum class LockReason {
    TOTAL_SCREEN_TIME_EXCEEDED,
    SCHEDULED_BEDTIME_CURFEW,
    PARENT_INSTANT_FREEZE
}
