package com.shieldchild.app.engine

import android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
import android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
import android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED
import android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE
import android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE
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
                UsageEventLite("app$i", "Main", ACTIVITY_PAUSED, midnight + i * 60_000L + 54_000L),
                UsageEventLite("app$i", "Main", ACTIVITY_STOPPED, midnight + i * 60_000L + 54_000L)
            )
        }
        val totals = UsageMonitor.aggregate(events, midnight, min(60), none)
        assertEquals(9L, totals.values.sum() / 60_000L)
    }

    @Test
    fun sessionStartedBeforeMidnightCountsFromMidnight() {
        val events = listOf(ev("yt", ACTIVITY_PAUSED, 20), ev("yt", ACTIVITY_STOPPED, 20))
        assertEquals(20 * 60_000L, UsageMonitor.aggregate(events, midnight, min(60), none)["yt"])
        // Only a stop today (it was paused before midnight, e.g. in PiP): still on screen from 00:00.
        assertEquals(20 * 60_000L, UsageMonitor.aggregate(listOf(ev("yt", ACTIVITY_STOPPED, 20)), midnight, min(60), none)["yt"])
    }

    @Test
    fun strayPauseLaterInTheDayIsNotBilledBackToMidnight() {
        // Package installer at 13:42: a screen pauses before it ever resumes.
        val events = listOf(
            ev("installer", ACTIVITY_RESUMED, 822, "Installing"),
            ev("installer", ACTIVITY_PAUSED, 823, "Installing"),
            ev("installer", ACTIVITY_PAUSED, 823, "Failed"),
            ev("installer", ACTIVITY_STOPPED, 823, "Installing"),
            ev("installer", ACTIVITY_RESUMED, 824, "Failed"),
            ev("installer", ACTIVITY_PAUSED, 825, "Failed"),
            ev("installer", ACTIVITY_STOPPED, 825, "Failed")
        )
        assertEquals(3 * 60_000L, UsageMonitor.aggregate(events, midnight, min(830), none)["installer"])
    }

    @Test
    fun appWithSeveralScreensIsNotDoubleCounted() {
        val events = listOf(
            ev("yt", ACTIVITY_RESUMED, 0, "Home"),
            ev("yt", ACTIVITY_RESUMED, 5, "Player"),   // second screen opens on top
            ev("yt", ACTIVITY_PAUSED, 5, "Home"),
            ev("yt", ACTIVITY_STOPPED, 6, "Home"),
            ev("yt", ACTIVITY_PAUSED, 15, "Player"),
            ev("yt", ACTIVITY_STOPPED, 15, "Player")
        )
        assertEquals(15 * 60_000L, UsageMonitor.aggregate(events, midnight, min(60), none)["yt"])
    }

    @Test
    fun appStillOpenCountsUntilNowAndLaunchersAreExcluded() {
        val events = listOf(
            ev("launcher", ACTIVITY_RESUMED, 0), ev("launcher", ACTIVITY_PAUSED, 30),
            ev("game", ACTIVITY_RESUMED, 30), ev("launcher", ACTIVITY_STOPPED, 30)
        )
        val totals = UsageMonitor.aggregate(events, midnight, min(45)) { it == "launcher" }
        assertEquals(mapOf("game" to 15 * 60_000L), totals)
    }

    @Test
    fun pictureInPictureOverHomeCountsUntilThePipCloses() {
        // As logged on the Lenovo tablet: Home pressed while a video plays, YouTube shrinks to PiP
        // (paused, not stopped), and only stops when the PiP is closed.
        val events = listOf(
            ev("yt", ACTIVITY_RESUMED, 0),
            ev("launcher", ACTIVITY_RESUMED, 10),
            ev("yt", ACTIVITY_PAUSED, 10),
            ev("yt", ACTIVITY_STOPPED, 40)
        )
        assertEquals(40 * 60_000L, UsageMonitor.aggregate(events, midnight, min(60)) { it == "launcher" }["yt"])
        // Still playing: counts until now.
        assertEquals(30 * 60_000L, UsageMonitor.aggregate(events.dropLast(1), midnight, min(30)) { it == "launcher" }["yt"])
    }

    @Test
    fun screenOffPausesPipTimeUntilThePipShowsAgain() {
        val events = listOf(
            ev("yt", ACTIVITY_RESUMED, 0),
            ev("yt", ACTIVITY_PAUSED, 10),          // into PiP
            ev("android", SCREEN_NON_INTERACTIVE, 20),
            ev("yt", ACTIVITY_STOPPED, 20),
            ev("android", SCREEN_INTERACTIVE, 50),
            ev("yt", ACTIVITY_PAUSED, 50),          // PiP visible again, no resume
            ev("yt", ACTIVITY_STOPPED, 55)
        )
        assertEquals(25 * 60_000L, UsageMonitor.aggregate(events, midnight, min(60), none)["yt"])
        // Even if a device never sends the stop, screen-off ends the time.
        val noStops = events.filter { it.type != ACTIVITY_STOPPED }
        assertEquals(30 * 60_000L, UsageMonitor.aggregate(noStops, midnight, min(60), none)["yt"])
    }

    @Test
    fun pipOverAnotherAppCountsForBothAppsButOnceForTheDevice() {
        val events = listOf(
            ev("yt", ACTIVITY_RESUMED, 0),
            ev("chrome", ACTIVITY_RESUMED, 10),
            ev("yt", ACTIVITY_PAUSED, 10),          // YouTube in PiP over Chrome
            ev("chrome", ACTIVITY_PAUSED, 30),
            ev("chrome", ACTIVITY_STOPPED, 30),
            ev("yt", ACTIVITY_STOPPED, 40)
        )
        val intervals = UsageMonitor.visibleIntervals(events, midnight, min(60), none)
        assertEquals(mapOf("yt" to 40 * 60_000L, "chrome" to 20 * 60_000L), UsageMonitor.perApp(intervals))
        assertEquals(40 * 60_000L, UsageMonitor.screenMillis(intervals))
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

    @Test
    fun creepingClockShiftsAgainstBootAnchorTriggerSuspiciousJump() {
        val anchorWall = 1_700_000_000_000L
        val anchorUptime = 5_000_000L

        // Single 90s jump (under 120s threshold)
        assertFalse(ClockGuard.isSuspiciousJump(anchorWall, anchorUptime, anchorWall + 5_000L - 90_000L, anchorUptime + 5_000L, isAutomaticTime = false))

        // Cumulative 180s jump (over 120s threshold) against persistent anchor
        assertTrue(ClockGuard.isSuspiciousJump(anchorWall, anchorUptime, anchorWall + 10_000L - 180_000L, anchorUptime + 10_000L, isAutomaticTime = false))
    }
}
