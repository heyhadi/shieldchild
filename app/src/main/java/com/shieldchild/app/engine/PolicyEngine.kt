package com.shieldchild.app.engine

import com.shieldchild.app.data.AccessState
import com.shieldchild.app.data.AppLimit
import com.shieldchild.app.data.DeviceCurfewPolicy
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.data.LockState
import java.util.Calendar

class PolicyEngine {

    /**
     * Evaluates all constraints to determine if device or a single app must be locked.
     */
    fun evaluateLockState(
        policy: DeviceCurfewPolicy,
        appLimits: Map<String, AppLimit>,
        usageSummary: DailyUsageSummary,
        foregroundPackage: String?,
        calendar: Calendar = Calendar.getInstance(),
        access: AccessState = AccessState()
    ): LockState {
        // A parent session lifts whole-phone locks only (via evaluateDeviceLock below);
        // blocked and used-up apps stay locked in case the phone is handed back unlocked.

        val deviceLockReason = evaluateDeviceLock(
            policy = policy,
            isDailyLimitReached = isDailyLimitReached(policy, usageSummary.totalDeviceUsageMinutes),
            cal = calendar,
            access = access
        )
        if (deviceLockReason != null) {
            return LockState.CurfewLock(
                reason = deviceLockReason,
                effectiveLimitMinutes = policy.effectiveDailyLimitMinutes,
                usedMinutes = usageSummary.totalDeviceUsageMinutes
            )
        }

        // Priority 4: Individual App Limit Exceeded
        // Extra time only lifts a used-up limit; a blocked app stays blocked until a parent unblocks it.
        val limit = foregroundPackage?.let { appLimits[it] }
        if (foregroundPackage != null && limit != null) {
            val usedMinutes = usageSummary.perAppUsageMinutes[foregroundPackage] ?: 0L
            val isUsedUp = foregroundPackage !in access.pausedApps && limit.dailyLimitMinutes in 1..usedMinutes

            if (!limit.isEssential && (limit.isBlocked || isUsedUp)) {
                return LockState.SingleAppLock(
                    packageName = foregroundPackage,
                    appName = limit.appName,
                    limitMinutes = limit.dailyLimitMinutes,
                    usedMinutes = usedMinutes,
                    isBlocked = limit.isBlocked
                )
            }
        }

        return LockState.Unlocked
    }

    /**
     * Single source of truth for whole-phone locks, used by the monitor service, the
     * accessibility service, boot/app start and the parent dashboard.
     * A parent session lifts everything. Otherwise: instant lock > bedtime > daily limit,
     * where a parent grant pauses only the lock it was given for.
     */
    fun evaluateDeviceLock(
        policy: DeviceCurfewPolicy,
        isDailyLimitReached: Boolean,
        cal: Calendar = Calendar.getInstance(),
        access: AccessState = AccessState()
    ): LockReason? {
        if (access.isParentSession) return null
        if (policy.isInstantLocked) return LockReason.PARENT_INSTANT_FREEZE
        if (!access.isBedtimePaused && policy.isCurfewScheduled && isWithinCurfewWindow(policy, cal)) {
            return LockReason.SCHEDULED_BEDTIME_CURFEW
        }
        if (!access.isLimitPaused && isDailyLimitReached && !policy.isWholeDayAllowed(dayName(cal))) {
            return LockReason.TOTAL_SCREEN_TIME_EXCEEDED
        }
        return null
    }

    fun isDailyLimitReached(policy: DeviceCurfewPolicy, usedMinutes: Long): Boolean =
        policy.effectiveDailyLimitMinutes in 1..usedMinutes

    fun dayName(cal: Calendar): String = DAY_NAMES[cal.get(Calendar.DAY_OF_WEEK) - 1]

    fun isWithinCurfewWindow(policy: DeviceCurfewPolicy, cal: Calendar = Calendar.getInstance()): Boolean {
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val startMinutes = policy.curfewStartHour * 60 + policy.curfewStartMinute
        val endMinutes = policy.curfewEndHour * 60 + policy.curfewEndMinute

        return if (startMinutes <= endMinutes) {
            // e.g. 13:00 to 15:00
            currentMinutes in startMinutes until endMinutes
        } else {
            // Overnight window, e.g. 21:00 to 07:00
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    companion object {
        // Matches the keys stored in DeviceCurfewPolicy.unlimitedDays.
        private val DAY_NAMES = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    }
}
