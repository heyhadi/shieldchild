package com.shieldchild.app.engine

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
        isCurfewOverrideActive: Boolean = false
    ): LockState {
        // Priority 1: Instant Parent Lockout (takes absolute priority)
        if (policy.isInstantLocked) {
            return LockState.CurfewLock(
                reason = LockReason.PARENT_INSTANT_FREEZE,
                effectiveLimitMinutes = policy.effectiveDailyLimitMinutes,
                usedMinutes = usageSummary.totalDeviceUsageMinutes
            )
        }

        // If parent granted temporary override (e.g. +15m bonus), bypass curfew and daily limits
        if (!isCurfewOverrideActive) {
            // Priority 2: Bedtime Curfew Schedule
            if (policy.isCurfewScheduled && isWithinCurfewWindow(policy, calendar)) {
                return LockState.CurfewLock(
                    reason = LockReason.SCHEDULED_BEDTIME_CURFEW,
                    effectiveLimitMinutes = policy.effectiveDailyLimitMinutes,
                    usedMinutes = usageSummary.totalDeviceUsageMinutes
                )
            }

            // Priority 3: Total Screen Time Daily Limit Exceeded
            if (policy.effectiveDailyLimitMinutes > 0 &&
                usageSummary.totalDeviceUsageMinutes >= policy.effectiveDailyLimitMinutes
            ) {
                return LockState.CurfewLock(
                    reason = LockReason.TOTAL_SCREEN_TIME_EXCEEDED,
                    effectiveLimitMinutes = policy.effectiveDailyLimitMinutes,
                    usedMinutes = usageSummary.totalDeviceUsageMinutes
                )
            }
        }

        // Priority 4: Individual App Limit Exceeded
        if (foregroundPackage != null && appLimits.containsKey(foregroundPackage)) {
            val limit = appLimits[foregroundPackage]!!
            val usedMinutes = usageSummary.perAppUsageMinutes[foregroundPackage] ?: 0L

            if (limit.isBlocked || (limit.dailyLimitMinutes in 1..usedMinutes)) {
                return LockState.SingleAppLock(
                    packageName = foregroundPackage,
                    appName = limit.appName,
                    limitMinutes = limit.dailyLimitMinutes,
                    usedMinutes = usedMinutes
                )
            }
        }

        return LockState.Unlocked
    }

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
}
