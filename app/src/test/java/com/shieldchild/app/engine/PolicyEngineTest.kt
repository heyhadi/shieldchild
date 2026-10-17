package com.shieldchild.app.engine

import com.shieldchild.app.data.AccessState
import com.shieldchild.app.data.AppLimit
import com.shieldchild.app.data.DeviceCurfewPolicy
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.data.LockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class PolicyEngineTest {

    private val engine = PolicyEngine()

    // 2026-10-14 is a Wednesday, 2026-10-17 a Saturday.
    private fun at(day: Int, hour: Int, minute: Int = 0): Calendar =
        Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute) }

    private val weekdayNoon = at(14, 12)
    private val saturdayNoon = at(17, 12)

    private val policy = DeviceCurfewPolicy(
        isDailyLimitEnabled = true,
        dailyTotalLimitMinutes = 120,
        unlimitedDays = setOf("Sat", "Sun"),
        isCurfewScheduled = true,
        curfewStartHour = 21,
        curfewEndHour = 7
    )

    private fun deviceLock(p: DeviceCurfewPolicy, used: Long, cal: Calendar, access: AccessState = AccessState()) =
        engine.evaluateDeviceLock(p, engine.isDailyLimitReached(p, used), cal, access)

    @Test
    fun dailyLimitLocksOnWeekday() {
        assertEquals(LockReason.TOTAL_SCREEN_TIME_EXCEEDED, deviceLock(policy, 120, weekdayNoon))
        assertNull(deviceLock(policy, 119, weekdayNoon))
    }

    @Test
    fun unlimitedDayIgnoresDailyLimit() {
        assertNull(deviceLock(policy, 500, saturdayNoon))
    }

    @Test
    fun disabledDailyLimitNeverLocks() {
        assertNull(deviceLock(policy.copy(isDailyLimitEnabled = false), 500, weekdayNoon))
    }

    @Test
    fun bonusMinutesRaiseTheLimit() {
        assertNull(deviceLock(policy.copy(bonusMinutesToday = 15), 130, weekdayNoon))
    }

    @Test
    fun overnightCurfewEdges() {
        assertNull(deviceLock(policy, 0, at(14, 20, 59)))
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, deviceLock(policy, 0, at(14, 21, 0)))
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, deviceLock(policy, 0, at(15, 6, 59)))
        assertNull(deviceLock(policy, 0, at(15, 7, 0)))
    }

    @Test
    fun grantsLiftOnlyTheirOwnLock() {
        val bedtimeOff = AccessState(isBedtimePaused = true)
        val limitOff = AccessState(isLimitPaused = true)
        // Bedtime paused but the limit is used up: still locked for the limit.
        assertEquals(LockReason.TOTAL_SCREEN_TIME_EXCEEDED, deviceLock(policy, 500, at(14, 22), bedtimeOff))
        assertNull(deviceLock(policy, 500, at(14, 22), AccessState(isBedtimePaused = true, isLimitPaused = true)))
        // Limit paused during the day, but bedtime still starts on time.
        assertNull(deviceLock(policy, 500, weekdayNoon, limitOff))
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, deviceLock(policy, 500, at(14, 21), limitOff))
        // Grants never lift an instant lock.
        assertEquals(
            LockReason.PARENT_INSTANT_FREEZE,
            deviceLock(policy.copy(isInstantLocked = true), 0, weekdayNoon, AccessState(true, true))
        )
    }

    @Test
    fun parentSessionLiftsPhoneLocksButKeepsAppLimits() {
        val session = AccessState(isParentSession = true)
        assertNull(deviceLock(policy.copy(isInstantLocked = true), 500, at(14, 22), session))
        val limits = mapOf("tiktok" to AppLimit("tiktok", "TikTok", 45, isBlocked = true))
        val usage = DailyUsageSummary(500, emptyMap())
        assertTrue(engine.evaluateLockState(policy, limits, usage, "tiktok", at(14, 22), session) is LockState.SingleAppLock)
        assertEquals(LockState.Unlocked, engine.evaluateLockState(policy, limits, usage, "maps", at(14, 22), session))
    }

    @Test
    fun rebootPathMatchesLivePath() {
        val reason = CurfewLockLauncher.evaluateRebootLockReason(policy, isDailyExceeded = true, cal = saturdayNoon)
        assertNull(reason)
    }

    @Test
    fun blockedAppLocksAndLimitedAppLocksWhenUsedUp() {
        val limits = mapOf(
            "tiktok" to AppLimit("tiktok", "TikTok", 45, isBlocked = true),
            "youtube" to AppLimit("youtube", "YouTube", 30)
        )
        val usage = DailyUsageSummary(10, mapOf("youtube" to 30L))
        assertTrue(engine.evaluateLockState(policy, limits, usage, "tiktok", weekdayNoon) is LockState.SingleAppLock)
        assertTrue(engine.evaluateLockState(policy, limits, usage, "youtube", weekdayNoon) is LockState.SingleAppLock)
        assertEquals(LockState.Unlocked, engine.evaluateLockState(policy, limits, usage, "maps", weekdayNoon))
    }

    @Test
    fun pausedAppSuppressesSingleAppLockUntilExpired() {
        val limits = mapOf(
            "youtube" to AppLimit("youtube", "YouTube", 30)
        )
        val usage = DailyUsageSummary(10, mapOf("youtube" to 35L))

        // When not paused, YouTube is locked
        assertTrue(engine.evaluateLockState(policy, limits, usage, "youtube", weekdayNoon) is LockState.SingleAppLock)

        // When in pausedApps, YouTube is unlocked
        val pausedAccess = AccessState(pausedApps = setOf("youtube"))
        assertEquals(LockState.Unlocked, engine.evaluateLockState(policy, limits, usage, "youtube", weekdayNoon, pausedAccess))

        // When pausedApps contains another app, YouTube remains locked
        val otherPausedAccess = AccessState(pausedApps = setOf("tiktok"))
        assertTrue(engine.evaluateLockState(policy, limits, usage, "youtube", weekdayNoon, otherPausedAccess) is LockState.SingleAppLock)
    }
}
