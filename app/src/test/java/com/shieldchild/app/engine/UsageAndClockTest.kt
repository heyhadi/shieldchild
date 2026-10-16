package com.shieldchild.app.engine

import android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
import android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
import android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageAndClockTest {
    private val midnight = 1_000_000_000L
    private fun min(m: Long) = midnight + m * 60_000L
    private fun ev(pkg: String, type: Int, atMin: Long, cls: String = "Main") = UsageEventLite(pkg, cls, type, min(atMin))
    private val none: (String) -> Boolean = { false }

    @Test
    fun shortSessionsAddUpInsteadOfRoundingToZero() {
        // Ten 54-second sessions in ten different apps = 9 minutes, not 0.
        val events = (0 until 10).flatMap { i ->
            listOf(
                UsageEventLite("app$i", "Main", ACTIVITY_RESUMED, midnight + i * 60_000L),
                UsageEventLite("app$i", "Main", ACTIVITY_PAUSED, midnight + i * 60_000L + 54_000L)
            )
        }
        val totals = UsageMonitor.aggregate(events, midnight, min(60), none)
        assertEquals(9L, totals.values.sum() / 60_000L)
    }

    @Test
    fun sessionStartedBeforeMidnightCountsFromMidnight() {
        val totals = UsageMonitor.aggregate(listOf(ev("yt", ACTIVITY_PAUSED, 20)), midnight, min(60), none)
        assertEquals(20 * 60_000L, totals["yt"])
    }

    @Test
    fun strayPauseLaterInTheDayIsNotBilledBackToMidnight() {
        // Package installer at 13:42: a screen pauses before it ever resumes.
        val events = listOf(
            ev("installer", ACTIVITY_RESUMED, 822, "Installing"),
            ev("installer", ACTIVITY_PAUSED, 823, "Installing"),
            ev("installer", ACTIVITY_PAUSED, 823, "Failed"),
            ev("installer", ACTIVITY_RESUMED, 824, "Failed"),
            ev("installer", ACTIVITY_PAUSED, 825, "Failed")
        )
        assertEquals(2 * 60_000L, UsageMonitor.aggregate(events, midnight, min(830), none)["installer"])
    }

    @Test
    fun appWithSeveralScreensIsNotDoubleCounted() {
        val events = listOf(
            ev("yt", ACTIVITY_RESUMED, 0, "Home"),
            ev("yt", ACTIVITY_RESUMED, 5, "Player"),   // second screen opens on top
            ev("yt", ACTIVITY_PAUSED, 5, "Home"),
            ev("yt", ACTIVITY_STOPPED, 6, "Home"),
            ev("yt", ACTIVITY_PAUSED, 15, "Player")
        )
        assertEquals(15 * 60_000L, UsageMonitor.aggregate(events, midnight, min(60), none)["yt"])
    }

    @Test
    fun appStillOpenCountsUntilNowAndLaunchersAreExcluded() {
        val events = listOf(ev("launcher", ACTIVITY_RESUMED, 0), ev("launcher", ACTIVITY_PAUSED, 30), ev("game", ACTIVITY_RESUMED, 30))
        val totals = UsageMonitor.aggregate(events, midnight, min(45)) { it == "launcher" }
        assertEquals(mapOf("game" to 15 * 60_000L), totals)
    }

    @Test
    fun clockJumpIsTamperingOnlyWithManualTime() {
        val wall = 1_700_000_000_000L
        val up = 5_000_000L
        // 10 s of uptime passed but the clock moved 8 hours forward.
        assertTrue(ClockGuard.isSuspiciousJump(wall, up, wall + 8 * 3_600_000L, up + 10_000L, isAutomaticTime = false))
        assertFalse(ClockGuard.isSuspiciousJump(wall, up, wall + 8 * 3_600_000L, up + 10_000L, isAutomaticTime = true))
        // Normal passage of time, plus a small correction.
        assertFalse(ClockGuard.isSuspiciousJump(wall, up, wall + 60_000L + 30_000L, up + 60_000L, isAutomaticTime = false))
        // No baseline yet.
        assertFalse(ClockGuard.isSuspiciousJump(0L, 0L, wall, up, isAutomaticTime = false))
    }
}
