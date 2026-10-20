package com.shieldchild.app.engine

import android.content.Context
import android.content.Intent
import com.shieldchild.app.data.GrantLog
import com.shieldchild.app.data.GrantMethod
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.data.PreferencesRepository
import com.shieldchild.app.service.ShieldMonitorService
import java.util.Calendar

/**
 * Everything a parent can do after authenticating on the lock screen. Unlocking never
 * gives the child time by itself; time is only granted by an explicit choice.
 */
object AccessGrants {

    /** Parent uses the phone; the child's allowance is untouched. */
    fun startParentSession(context: Context, repo: PreferencesRepository, method: GrantMethod) {
        repo.startParentSession()
        repo.addGrantLog(method, GrantLog.PARENT_ACCESS)
        recheck(context)
    }

    fun endParentSession(context: Context, repo: PreferencesRepository) {
        repo.endParentSession()
        recheck(context)
    }

    /**
     * Gives the child [minutes] of use. Lifts only what blocks the phone right now:
     * during bedtime the bedtime lock (and the daily limit, or the time would be useless);
     * otherwise only the daily limit, so a bedtime starting mid-grant still locks.
     */
    fun grantMinutes(
        context: Context,
        repo: PreferencesRepository,
        minutes: Long,
        reason: LockReason,
        method: GrantMethod,
        now: Long = System.currentTimeMillis(),
        lockedPackage: String? = null
    ) {
        val until = now + minutes * 60_000L
        if (lockedPackage != null) {
            val deviceExceeded = repo.isDailyLimitExceededToday()
            repo.pauseAppLimitUntil(lockedPackage, until)
            // Only lift whole-device lock if whole-device limit/curfew is also active right now
            if (deviceExceeded || reason == LockReason.SCHEDULED_BEDTIME_CURFEW || reason == LockReason.PARENT_INSTANT_FREEZE || repo.getCurfewPolicy().isInstantLocked) {
                applyGrant(repo, reason, until)
            }
        } else {
            // Only a pause: "+ bonus" in the dashboard is the one way to raise the limit.
            applyGrant(repo, reason, until)
        }
        repo.addGrantLog(method, GrantLog.minutes(minutes))
        recheck(context)
    }

    /** Daily limit off until midnight; during bedtime, bedtime off until it would end. */
    fun grantRestOfToday(
        context: Context,
        repo: PreferencesRepository,
        reason: LockReason,
        method: GrantMethod,
        cal: Calendar = Calendar.getInstance(),
        lockedPackage: String? = null
    ) {
        val until = if (reason == LockReason.SCHEDULED_BEDTIME_CURFEW) {
            nextCurfewEnd(repo, cal)
        } else {
            (cal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        if (lockedPackage != null) {
            val deviceExceeded = repo.isDailyLimitExceededToday()
            repo.pauseAppLimitUntil(lockedPackage, until)
            if (deviceExceeded || reason == LockReason.SCHEDULED_BEDTIME_CURFEW || reason == LockReason.PARENT_INSTANT_FREEZE || repo.getCurfewPolicy().isInstantLocked) {
                applyGrant(repo, reason, until)
            }
        } else {
            applyGrant(repo, reason, until)
        }
        repo.addGrantLog(method, if (reason == LockReason.SCHEDULED_BEDTIME_CURFEW) GrantLog.BEDTIME_OFF else GrantLog.REST_OF_TODAY)
        recheck(context)
    }

    /**
     * Dashboard "+ bonus": raises today's limit. Clears the saved limit-reached flag so the
     * accessibility and boot paths don't relock with it; the recheck sets it again if still over.
     */
    fun addBonusMinutes(context: Context, repo: PreferencesRepository, minutes: Long) {
        repo.addBonusMinutes(minutes)
        repo.setDailyLimitExceededToday(false)
        recheck(context)
    }

    fun turnOffInstantLock(
        context: Context,
        repo: PreferencesRepository,
        method: GrantMethod,
        now: Long = System.currentTimeMillis()
    ) {
        repo.setInstantLock(false)
        if (repo.isDailyLimitExceededToday()) {
            repo.pauseLimitUntil(maxOf(now + 15 * 60_000L, repo.getLimitPausedUntil()))
            repo.setDailyLimitExceededToday(false)
        }
        repo.addGrantLog(method, GrantLog.INSTANT_OFF)
        recheck(context)
    }

    private fun applyGrant(repo: PreferencesRepository, reason: LockReason, until: Long) {
        when (reason) {
            LockReason.SCHEDULED_BEDTIME_CURFEW -> {
                repo.pauseBedtimeUntil(maxOf(until, repo.getBedtimePausedUntil()))
                repo.pauseLimitUntil(maxOf(until, repo.getLimitPausedUntil()))
            }
            LockReason.TOTAL_SCREEN_TIME_EXCEEDED -> {
                repo.pauseLimitUntil(maxOf(until, repo.getLimitPausedUntil()))
            }
            LockReason.PARENT_INSTANT_FREEZE -> {
                repo.pauseLimitUntil(maxOf(until, repo.getLimitPausedUntil()))
                repo.pauseBedtimeUntil(maxOf(until, repo.getBedtimePausedUntil()))
            }
        }
        // Granted time pauses an instant lock instead of turning it off: it returns when the time ends.
        if (repo.getCurfewPolicy().isInstantLocked) {
            repo.pauseInstantLockUntil(maxOf(until, repo.getInstantPausedUntil()))
        }
        repo.setDailyLimitExceededToday(false)
    }

    internal fun nextCurfewEnd(repo: PreferencesRepository, cal: Calendar): Long {
        val policy = repo.getCurfewPolicy()
        val end = (cal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, policy.curfewEndHour)
            set(Calendar.MINUTE, policy.curfewEndMinute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (!end.after(cal)) end.add(Calendar.DAY_OF_YEAR, 1)
        return end.timeInMillis
    }

    private fun recheck(context: Context) {
        try {
            context.startService(Intent(context, ShieldMonitorService::class.java).apply {
                action = ShieldMonitorService.ACTION_RECHECK_POLICIES
            })
        } catch (_: Exception) {}
    }
}
