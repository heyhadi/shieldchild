package com.shieldchild.app.engine

import android.content.ContextWrapper
import com.shieldchild.app.data.AppLimit
import com.shieldchild.app.data.GrantMethod
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.data.LockState
import com.shieldchild.app.data.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** One test per row of docs/LOCK_RULES.md. Default policy: 120 min/day, Sat/Sun unlimited, bedtime 21:00–07:00. */
class LockRulesTest {

    private var uptime = 1_000_000L
    private val repo = testRepo { uptime }
    private val context = ContextWrapper(null)
    private val engine = PolicyEngine()

    private val game = "com.game"
    private val video = "com.video"
    private val school = "com.school"
    private val limits = mapOf(
        game to AppLimit(game, "Game", dailyLimitMinutes = 30),
        video to AppLimit(video, "Video", dailyLimitMinutes = 0, isBlocked = true),
        school to AppLimit(school, "School", dailyLimitMinutes = 10, isBlocked = true, isEssential = true)
    )

    // 2026-10-14 is a Wednesday, 2026-10-17 a Saturday.
    private fun at(day: Int, hour: Int, minute: Int = 0): Calendar =
        Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute) }

    private val noon = at(14, 12)
    private val bedtime = at(14, 22)

    private fun Calendar.plusMinutes(m: Int) = (clone() as Calendar).apply { add(Calendar.MINUTE, m) }

    private fun lock(cal: Calendar, totalUsed: Long = 0, fg: String? = null, appUsed: Long = 0): LockState =
        engine.evaluateLockState(
            policy = repo.getCurfewPolicy(),
            appLimits = limits,
            usageSummary = DailyUsageSummary(totalUsed, if (fg != null) mapOf(fg to appUsed) else emptyMap()),
            foregroundPackage = fg,
            calendar = cal,
            access = repo.getAccessState(cal.timeInMillis)
        )

    private fun reason(state: LockState) = (state as? LockState.CurfewLock)?.reason

    private fun grant(cal: Calendar, minutes: Long, reason: LockReason, app: String? = null) =
        AccessGrants.grantMinutes(context, repo, minutes, reason, GrantMethod.PIN, cal.timeInMillis, app)

    // --- Locks ---

    @Test
    fun instantLockIsStrongestAndAllowsNoEssentialApps() {
        repo.setInstantLock(true)
        assertEquals(LockReason.PARENT_INSTANT_FREEZE, reason(lock(bedtime, totalUsed = 500)))
        assertTrue(repo.getEssentialAppsForReason(LockReason.PARENT_INSTANT_FREEZE).isEmpty())
    }

    @Test
    fun bedtimeBeatsDailyLimitAndAllowsOnlyBedtimeEssentials() {
        repo.saveAppLimits(listOf(
            AppLimit("com.alarm", "Alarm", 0, isEssential = true, allowInBedtime = true),
            AppLimit("com.notes", "Notes", 0, isEssential = true)
        ))
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, reason(lock(bedtime, totalUsed = 500)))
        assertEquals(listOf("com.alarm"), repo.getEssentialAppsForReason(LockReason.SCHEDULED_BEDTIME_CURFEW).map { it.packageName })
        assertEquals(2, repo.getEssentialAppsForReason(LockReason.TOTAL_SCREEN_TIME_EXCEEDED).size)
    }

    @Test
    fun dailyLimitLocksOnWeekdaysOnly() {
        assertEquals(LockReason.TOTAL_SCREEN_TIME_EXCEEDED, reason(lock(noon, totalUsed = 120)))
        assertEquals(LockState.Unlocked, lock(noon, totalUsed = 119))
        assertEquals(LockState.Unlocked, lock(at(17, 12), totalUsed = 500))
    }

    @Test
    fun bedtimeStillAppliesOnUnlimitedDays() {
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, reason(lock(at(17, 22))))
    }

    @Test
    fun blockedAppLocksButEssentialAppsNever() {
        assertTrue((lock(noon, fg = video) as LockState.SingleAppLock).isBlocked)
        assertEquals(LockState.Unlocked, lock(noon, fg = school, appUsed = 99))
    }

    @Test
    fun appOutOfTimeLocksOnlyThatApp() {
        assertTrue(lock(noon, fg = game, appUsed = 30) is LockState.SingleAppLock)
        assertEquals(LockState.Unlocked, lock(noon, fg = game, appUsed = 29))
        assertEquals(LockState.Unlocked, lock(noon, fg = "com.other", appUsed = 999))
    }

    // --- Use the phone myself ---

    @Test
    fun parentSessionLiftsEverythingButBlockedApps() {
        repo.setInstantLock(true)
        AccessGrants.startParentSession(context, repo, GrantMethod.PIN)
        assertEquals(LockState.Unlocked, lock(bedtime, totalUsed = 500))
        assertEquals(LockState.Unlocked, lock(noon, totalUsed = 500, fg = game, appUsed = 99))
        assertTrue(lock(noon, fg = video) is LockState.SingleAppLock)
    }

    @Test
    fun parentSessionEndsAfterTenMinutes() {
        AccessGrants.startParentSession(context, repo, GrantMethod.PIN)
        uptime += PreferencesRepository.PARENT_SESSION_MAX_MS
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, reason(lock(bedtime)))
    }

    @Test
    fun turningInstantLockOnEndsParentSession() {
        AccessGrants.startParentSession(context, repo, GrantMethod.PIN)
        repo.setInstantLock(true)
        assertEquals(LockReason.PARENT_INSTANT_FREEZE, reason(lock(noon)))
    }

    // --- Minutes or a code ---

    @Test
    fun minutesDuringInstantLockPauseItAndItReturns() {
        repo.setInstantLock(true)
        grant(noon, 15, LockReason.PARENT_INSTANT_FREEZE)
        assertEquals(LockState.Unlocked, lock(noon.plusMinutes(14)))
        assertEquals(LockReason.PARENT_INSTANT_FREEZE, reason(lock(noon.plusMinutes(15))))
    }

    @Test
    fun minutesAtBedtimeLiftBedtimeThenItReturns() {
        grant(bedtime, 15, LockReason.SCHEDULED_BEDTIME_CURFEW)
        assertEquals(LockState.Unlocked, lock(bedtime.plusMinutes(14), totalUsed = 500))
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, reason(lock(bedtime.plusMinutes(15))))
    }

    @Test
    fun minutesForDailyLimitOnlyPauseIt() {
        val start = at(14, 20, 30)
        grant(start, 15, LockReason.TOTAL_SCREEN_TIME_EXCEEDED)
        assertEquals(0L, repo.getCurfewPolicy().bonusMinutesToday)
        assertEquals(LockState.Unlocked, lock(start.plusMinutes(14), totalUsed = 134))
        assertEquals(LockReason.TOTAL_SCREEN_TIME_EXCEEDED, reason(lock(start.plusMinutes(15), totalUsed = 135)))
    }

    @Test
    fun minutesForDailyLimitDontLiftBedtimeStartingDuringThem() {
        grant(at(14, 20, 50), 30, LockReason.TOTAL_SCREEN_TIME_EXCEEDED)
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, reason(lock(at(14, 21, 5), totalUsed = 500)))
    }

    @Test
    fun minutesForAnAppLiftOnlyThatApp() {
        grant(noon, 15, LockReason.TOTAL_SCREEN_TIME_EXCEEDED, app = game)
        assertEquals(LockState.Unlocked, lock(noon.plusMinutes(10), fg = game, appUsed = 40))
        assertTrue(lock(noon.plusMinutes(15), fg = game, appUsed = 45) is LockState.SingleAppLock)
        assertEquals(LockReason.TOTAL_SCREEN_TIME_EXCEEDED, reason(lock(noon.plusMinutes(10), totalUsed = 500)))
    }

    @Test
    fun minutesNeverUnblockAnApp() {
        grant(noon, 60, LockReason.TOTAL_SCREEN_TIME_EXCEEDED, app = video)
        assertTrue(lock(noon.plusMinutes(1), fg = video) is LockState.SingleAppLock)
    }

    @Test
    fun turningInstantLockOnCancelsAGrant() {
        grant(noon, 60, LockReason.PARENT_INSTANT_FREEZE)
        repo.setInstantLock(true)
        assertEquals(LockReason.PARENT_INSTANT_FREEZE, reason(lock(noon.plusMinutes(1))))
    }

    @Test
    fun wholePhoneGrantRunsPastMidnight() {
        grant(at(14, 23, 30), 60, LockReason.SCHEDULED_BEDTIME_CURFEW)
        assertEquals(LockState.Unlocked, lock(at(15, 0, 20)))
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, reason(lock(at(15, 0, 30))))
    }

    // --- Rest of today ---

    @Test
    fun restOfTodayLiftsDailyLimitUntilMidnight() {
        AccessGrants.grantRestOfToday(context, repo, LockReason.TOTAL_SCREEN_TIME_EXCEEDED, GrantMethod.PIN, noon)
        assertEquals(LockState.Unlocked, lock(at(14, 20), totalUsed = 500))
        assertEquals(LockReason.TOTAL_SCREEN_TIME_EXCEEDED, reason(lock(at(15, 12), totalUsed = 500)))
    }

    @Test
    fun restOfTodayAtBedtimeLastsUntilBedtimeEnds() {
        AccessGrants.grantRestOfToday(context, repo, LockReason.SCHEDULED_BEDTIME_CURFEW, GrantMethod.PIN, bedtime)
        assertEquals(LockState.Unlocked, lock(at(15, 6, 59)))
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, reason(lock(at(15, 21))))
    }

    @Test
    fun restOfTodayDuringInstantLockLastsUntilMidnight() {
        repo.setInstantLock(true)
        AccessGrants.grantRestOfToday(context, repo, LockReason.PARENT_INSTANT_FREEZE, GrantMethod.PIN, noon)
        assertEquals(LockState.Unlocked, lock(at(14, 23, 59)))
        assertEquals(LockReason.PARENT_INSTANT_FREEZE, reason(lock(at(15, 0, 1))))
    }

    @Test
    fun restOfTodayForAnAppLastsUntilMidnight() {
        AccessGrants.grantRestOfToday(context, repo, LockReason.TOTAL_SCREEN_TIME_EXCEEDED, GrantMethod.PIN, noon, lockedPackage = game)
        assertEquals(LockState.Unlocked, lock(at(14, 20), fg = game, appUsed = 300))
        assertTrue(lock(at(14, 20), fg = video) is LockState.SingleAppLock)
    }

    // --- Turn off instant lock, dashboard ---

    @Test
    fun turningOffInstantLockIsPermanentAndGivesFifteenMinutesIfLimitReached() {
        repo.setInstantLock(true)
        repo.setDailyLimitExceededToday(true)
        AccessGrants.turnOffInstantLock(context, repo, GrantMethod.PIN, noon.timeInMillis)
        assertEquals(LockState.Unlocked, lock(noon.plusMinutes(14), totalUsed = 500))
        assertEquals(LockReason.TOTAL_SCREEN_TIME_EXCEEDED, reason(lock(noon.plusMinutes(15), totalUsed = 500)))
        assertEquals(LockState.Unlocked, lock(at(14, 18)))
    }

    @Test
    fun dashboardBonusRaisesTodaysLimit() {
        AccessGrants.addBonusMinutes(context, repo, 30)
        assertEquals(LockState.Unlocked, lock(noon, totalUsed = 149))
        assertEquals(LockReason.TOTAL_SCREEN_TIME_EXCEEDED, reason(lock(noon, totalUsed = 150)))
    }

    @Test
    fun unblockingInDashboardOpensTheApp() {
        repo.setAppLimit(limits.getValue(video).copy(isBlocked = false))
        val state = engine.evaluateLockState(repo.getCurfewPolicy(), repo.getAppLimits(), DailyUsageSummary(0, emptyMap()), video, noon, repo.getAccessState(noon.timeInMillis))
        assertEquals(LockState.Unlocked, state)
    }
}
