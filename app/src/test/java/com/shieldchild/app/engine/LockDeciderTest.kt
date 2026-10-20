package com.shieldchild.app.engine

import android.content.ContextWrapper
import com.shieldchild.app.data.AppLimit
import com.shieldchild.app.data.GrantMethod
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.data.LockState
import com.shieldchild.app.data.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Every place that decides whether to lock asks LockDecider. These tests check that the questions
 * each place asks (monitor: decide; accessibility, dashboard: deviceLock; picture-in-picture:
 * isAppLocked; lock screen: lockScreenUpdate; boot and app start: startupSnapshot) agree.
 */
class LockDeciderTest {

    private var uptime = 1_000_000L
    private lateinit var repo: PreferencesRepository
    private val context = ContextWrapper(null)

    private val game = "com.game"
    private val video = "com.video"
    private val school = "com.school"

    // 2026-10-14 is a Wednesday, 2026-10-17 a Saturday.
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute) }.timeInMillis

    private val noon = at(14, 12)

    @Before
    fun setUp() {
        LockDecider.clearCachedUsage()
        repo = testRepo { uptime }
        repo.saveAppLimits(listOf(
            AppLimit(game, "Game", dailyLimitMinutes = 30),
            AppLimit(video, "Video", dailyLimitMinutes = 0, isBlocked = true),
            AppLimit(school, "School", dailyLimitMinutes = 10, isBlocked = true, isEssential = true)
        ))
    }

    private fun usage(total: Long, gameUsed: Long = 0) = DailyUsageSummary(total, mapOf(game to gameUsed, school to 60))

    /** Checks every caller's answer against the monitor service's for one moment. */
    private fun assertAllAgree(now: Long, total: Long, gameUsed: Long = 0) {
        // Each moment stands alone: forget usage from the previous one.
        LockDecider.clearCachedUsage()
        repo.saveUsageMinutesToday(0, now)
        // The monitor service measures usage and shares it; everyone else decides from that.
        val shared = LockDecider.refreshUsage(repo, now) { usage(total, gameUsed) }
        val monitor = { fg: String? -> LockDecider.decide(LockDecider.snapshot(repo, shared, fg, now)) }
        val snapshot = LockDecider.snapshot(repo, LockDecider.usage(repo, now) { error("cache should be used") }, null, now)

        val device = (monitor(null) as? LockState.CurfewLock)?.reason
        assertEquals("accessibility / dashboard", device, LockDecider.deviceLock(snapshot))
        assertEquals("boot / app start", device, LockDecider.deviceLock(LockDecider.startupSnapshot(repo, now)))
        assertEquals(
            "whole-phone lock screen",
            if (device == null) LockScreenUpdate.Close else LockScreenUpdate.ShowDeviceLock(device),
            LockDecider.lockScreenUpdate(snapshot, lockedPackage = null, parentChoosing = false)
        )

        for (pkg in listOf(game, video, school, "com.other")) {
            val forApp = monitor(pkg)
            if (device != null) assertEquals(LockState.CurfewLock::class, forApp::class)
            val appLocked = forApp is LockState.SingleAppLock
            assertEquals("picture-in-picture $pkg", appLocked, LockDecider.isAppLocked(snapshot, pkg))
            val expected = when {
                device != null -> LockScreenUpdate.ShowDeviceLock(device)
                appLocked -> LockScreenUpdate.Keep
                else -> LockScreenUpdate.Close
            }
            assertEquals("app lock screen $pkg", expected, LockDecider.lockScreenUpdate(snapshot, pkg, parentChoosing = false))
        }
    }

    private fun everyMoment(check: (now: Long, total: Long, gameUsed: Long) -> Unit) {
        for (now in listOf(noon, at(14, 22), at(17, 12), at(15, 6, 59))) {
            for ((total, gameUsed) in listOf(0L to 0L, 119L to 29L, 120L to 30L, 500L to 300L)) {
                check(now, total, gameUsed)
            }
        }
    }

    @Test
    fun allPlacesAgreeWithNoGrants() = everyMoment { now, total, used -> assertAllAgree(now, total, used) }

    @Test
    fun allPlacesAgreeDuringInstantLock() {
        repo.setInstantLock(true)
        everyMoment { now, total, used -> assertAllAgree(now, total, used) }
    }

    @Test
    fun allPlacesAgreeDuringParentSession() {
        repo.startParentSession()
        everyMoment { now, total, used -> assertAllAgree(now, total, used) }
    }

    @Test
    fun allPlacesAgreeAfterGrantsAndBonus() {
        AccessGrants.grantMinutes(context, repo, 30, LockReason.TOTAL_SCREEN_TIME_EXCEEDED, GrantMethod.PIN, noon, lockedPackage = game)
        AccessGrants.addBonusMinutes(context, repo, 15)
        for (now in listOf(noon, noon + 29 * 60_000L, noon + 31 * 60_000L)) {
            for (total in listOf(0L, 130L, 135L, 500L)) assertAllAgree(now, total, gameUsed = 45)
        }
        AccessGrants.grantMinutes(context, repo, 60, LockReason.SCHEDULED_BEDTIME_CURFEW, GrantMethod.PIN, at(14, 22))
        for (now in listOf(at(14, 22, 30), at(14, 23, 1))) assertAllAgree(now, 500, gameUsed = 45)
    }

    @Test
    fun parentChoosingKeepsTheLockScreenOpen() {
        val snapshot = LockDecider.snapshot(repo, usage(0), null, noon)
        assertEquals(LockScreenUpdate.Keep, LockDecider.lockScreenUpdate(snapshot, null, parentChoosing = true))
    }

    // --- The shared usage ---

    @Test
    fun afterRebootTheSavedTotalStillLocksBeforeUsageStatsCanBeRead() {
        LockDecider.refreshUsage(repo, noon) { usage(130) }
        LockDecider.clearCachedUsage() // new process
        assertEquals(LockReason.TOTAL_SCREEN_TIME_EXCEEDED, LockDecider.deviceLock(LockDecider.startupSnapshot(repo, noon + 60_000L)))
        // Usage stats are unreadable until the phone is unlocked: the saved total still counts.
        val usage = LockDecider.refreshUsage(repo, noon + 60_000L) { throw IllegalStateException("user locked") }
        assertEquals(130L, usage.totalDeviceUsageMinutes)
    }

    @Test
    fun aBonusUnlocksBootAndAppStartAtOnce() {
        LockDecider.refreshUsage(repo, noon) { usage(130) }
        assertEquals(LockReason.TOTAL_SCREEN_TIME_EXCEEDED, LockDecider.deviceLock(LockDecider.startupSnapshot(repo, noon)))
        AccessGrants.addBonusMinutes(context, repo, 30)
        assertNull(LockDecider.deviceLock(LockDecider.startupSnapshot(repo, noon)))
    }

    @Test
    fun savedUsageIsForgottenTheNextDay() {
        LockDecider.refreshUsage(repo, noon) { usage(130) }
        LockDecider.clearCachedUsage()
        assertEquals(0L, repo.getSavedUsageMinutesToday(at(15, 12)))
        assertNull(LockDecider.deviceLock(LockDecider.startupSnapshot(repo, at(15, 12))))
    }

    @Test
    fun sharedUsageIsReusedForThirtySecondsThenMeasuredAgain() {
        var queries = 0
        val query = { queries++; usage(10) }
        LockDecider.refreshUsage(repo, noon, query)
        LockDecider.usage(repo, noon + LockDecider.USAGE_MAX_AGE_MS, query)
        assertEquals(1, queries)
        LockDecider.usage(repo, noon + LockDecider.USAGE_MAX_AGE_MS + 1, query)
        assertEquals(2, queries)
    }

    @Test
    fun sharedUsageIsNotReusedAcrossMidnight() {
        var queries = 0
        val query = { queries++; usage(10) }
        LockDecider.refreshUsage(repo, at(14, 23, 59) + 50_000L, query)
        LockDecider.usage(repo, at(15, 0, 0) + 5_000L, query)
        assertEquals(2, queries)
        assertFalse(LockDecider.isAppLocked(LockDecider.snapshot(repo, usage(0), null, noon), game))
        assertTrue(LockDecider.isAppLocked(LockDecider.snapshot(repo, usage(0), null, noon), video))
    }
}
