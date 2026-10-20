package com.shieldchild.app.engine

import android.content.ContextWrapper
import com.shieldchild.app.data.AppLimit
import com.shieldchild.app.data.GrantMethod
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.data.LockState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** The glue between the lock screen, the overlay and the grants, modelled on reported bugs. */
class LockFlowTest {

    private val repo = testRepo()
    private val context = ContextWrapper(null)
    private val engine = PolicyEngine()
    private val youtube = "com.google.android.youtube"
    private val own = "com.shieldchild.app"
    private val limits = mapOf(youtube to AppLimit(youtube, "YouTube", dailyLimitMinutes = 30))

    // 2026-10-14 is a Wednesday.
    private val bedtime = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 14, 22, 0) }
    private val now = bedtime.timeInMillis

    // --- Lock screen relaunches (bug: 15 min given, lock screen comes back) ---

    @Test
    fun relaunchWithoutExtrasKeepsLockedAppAndReason() {
        val shown = LockIntentState.from(null, youtube, "YouTube", null)
        // bringToFront() runs whenever a PIN or choice dialog takes focus; it carries no extras.
        val relaunched = LockIntentState.from(shown, null, null, null)
        assertEquals(youtube, relaunched.lockedPackage)
        assertEquals("YouTube", relaunched.lockedAppName)
    }

    @Test
    fun relaunchWithoutExtrasKeepsBedtimeReason() {
        val shown = LockIntentState.from(null, null, null, LockReason.SCHEDULED_BEDTIME_CURFEW.name)
        val relaunched = LockIntentState.from(shown, null, null, null)
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, relaunched.reason)
    }

    @Test
    fun wholePhoneLockReplacesAppLock() {
        val shown = LockIntentState.from(null, youtube, "YouTube", null)
        val bedtimeLaunch = LockIntentState.from(shown, null, null, LockReason.SCHEDULED_BEDTIME_CURFEW.name)
        assertNull(bedtimeLaunch.lockedPackage)
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, bedtimeLaunch.reason)
    }

    @Test
    fun firstLaunchWithoutReasonDefaultsToDailyLimit() {
        assertEquals(LockReason.TOTAL_SCREEN_TIME_EXCEEDED, LockIntentState.from(null, youtube, "YouTube", null).reason)
        assertEquals(LockReason.TOTAL_SCREEN_TIME_EXCEEDED, LockIntentState.from(null, null, null, "garbage").reason)
    }

    @Test
    fun grantingMinutesOnAppLockUnlocksTheApp() {
        val state = LockIntentState.from(LockIntentState.from(null, youtube, "YouTube", null), null, null, null)
        AccessGrants.grantMinutes(context, repo, 15, state.reason, GrantMethod.PIN, now, state.lockedPackage)
        assertEquals(LockState.Unlocked, appLock(usedMinutes = 45))
    }

    @Test
    fun grantingMinutesNeverUnblocksABlockedApp() {
        val blocked = mapOf(youtube to AppLimit(youtube, "YouTube", dailyLimitMinutes = 0, isBlocked = true))
        AccessGrants.grantMinutes(context, repo, 15, LockReason.TOTAL_SCREEN_TIME_EXCEEDED, GrantMethod.REMOTE_CODE, now, youtube)
        val state = appLock(usedMinutes = 0, appLimits = blocked)
        assertTrue(state is LockState.SingleAppLock && state.isBlocked)
    }

    @Test
    fun grantingMinutesAtBedtimeLiftsBedtime() {
        val state = LockIntentState.from(LockIntentState.from(null, null, null, LockReason.SCHEDULED_BEDTIME_CURFEW.name), null, null, null)
        AccessGrants.grantMinutes(context, repo, 15, state.reason, GrantMethod.PIN, now, state.lockedPackage)
        assertNull(deviceLock())
    }

    @Test
    fun grantWithWrongReasonAtBedtimeStillLocks() {
        // Why the reason must survive relaunches: a daily-limit grant doesn't lift bedtime.
        AccessGrants.grantMinutes(context, repo, 15, LockReason.TOTAL_SCREEN_TIME_EXCEEDED, GrantMethod.PIN, now)
        assertEquals(LockReason.SCHEDULED_BEDTIME_CURFEW, deviceLock())
    }

    // --- Which app is in front (bug: YouTube keeps playing, lock flashes) ---

    @Test
    fun overlayWindowEventDoesNotReplaceTheLockedApp() {
        val reported = ForegroundTracker.reportedPackage(own, own, isAppLockOverlayShowing = true)
        assertNull(reported)
        val fg = reported ?: youtube
        assertTrue(appLock(usedMinutes = 45, fg = fg) is LockState.SingleAppLock)
    }

    @Test
    fun ownActivitiesAreStillReported() {
        // The dashboard and lock screen must be seen, or the lock would cover a signed-in parent.
        assertEquals(own, ForegroundTracker.reportedPackage(own, own, isAppLockOverlayShowing = false))
        assertEquals(youtube, ForegroundTracker.reportedPackage(youtube, own, isAppLockOverlayShowing = true))
    }

    // --- Picture-in-picture over Home (bug: overlay covers Home, "Go to Home" loops) ---

    private val launcher = "com.android.launcher3"
    private val limited = setOf(youtube)

    @Test
    fun lockedAppInPipOverHomeIsClosedNotReported() {
        val windows = listOf(ScreenWindow(launcher, false), ScreenWindow(youtube, true))
        val decision = HomeWindows.decide(launcher, windows, limited, isLocked = { true })
        assertEquals(launcher, decision.reportedPackage)
        assertEquals(youtube, decision.closePipPackage)
        // With Home reported, there's no app lock for the overlay to show over the launcher.
        assertEquals(LockState.Unlocked, appLock(usedMinutes = 45, fg = decision.reportedPackage))
    }

    @Test
    fun appInPipWithTimeLeftKeepsPlaying() {
        val windows = listOf(ScreenWindow(launcher, false), ScreenWindow(youtube, true))
        assertEquals(HomeDecision(launcher, null), HomeWindows.decide(launcher, windows, limited, isLocked = { false }))
    }

    @Test
    fun limitedAppBesideHomeInSplitScreenIsStillReported() {
        val windows = listOf(ScreenWindow(launcher, false), ScreenWindow(youtube, false))
        assertEquals(HomeDecision(youtube, null), HomeWindows.decide(launcher, windows, limited, isLocked = { true }))
    }

    @Test
    fun pipWindowEventOverHomeMeansHomeNotTheApp() {
        // Bug: after a parent session, tapping the PiP put YouTube's lock over Home.
        val launchers = setOf(launcher)
        val overHome = listOf(ScreenWindow(youtube, true), ScreenWindow(launcher, false))
        assertEquals(launcher, HomeWindows.launcherUnderPip(overHome, launchers))
        // Over another app, that app is still in front; the PiP event changes nothing.
        val overApp = listOf(ScreenWindow(youtube, true), ScreenWindow("com.example.game", false), ScreenWindow(launcher, false))
        assertNull(HomeWindows.launcherUnderPip(overApp, launchers))
    }

    // --- "Use the phone myself" on an app lock (bug: overlay returns at once) ---

    @Test
    fun parentSessionOpensTheUsedUpApp() {
        // The lock screen closes for a parent session; the engine must agree or the overlay returns.
        AccessGrants.startParentSession(context, repo, GrantMethod.PIN)
        assertTrue(repo.getAccessState().isParentSession)
        assertEquals(LockState.Unlocked, appLock(usedMinutes = 45, access = repo.getAccessState()))
    }

    @Test
    fun parentSessionNeverUnblocksABlockedApp() {
        val blocked = mapOf(youtube to AppLimit(youtube, "YouTube", dailyLimitMinutes = 0, isBlocked = true))
        AccessGrants.startParentSession(context, repo, GrantMethod.PIN)
        val state = appLock(usedMinutes = 0, appLimits = blocked, access = repo.getAccessState())
        assertTrue(state is LockState.SingleAppLock && state.isBlocked)
    }

    @Test
    fun usedUpAppLocksAgainWhenParentSessionEnds() {
        AccessGrants.startParentSession(context, repo, GrantMethod.PIN)
        AccessGrants.endParentSession(context, repo)
        assertTrue(appLock(usedMinutes = 45, access = repo.getAccessState()) is LockState.SingleAppLock)
    }

    // --- Dashboard "+ bonus" (bug: stale limit-reached flag relocks briefly) ---

    @Test
    fun dashboardBonusClearsTheLimitReachedFlag() {
        repo.setDailyLimitExceededToday(true)
        val before = repo.getCurfewPolicy().bonusMinutesToday
        AccessGrants.addBonusMinutes(context, repo, 15)
        assertEquals(before + 15, repo.getCurfewPolicy().bonusMinutesToday)
        assertTrue(!repo.isDailyLimitExceededToday())
        assertNull(CurfewLockLauncher.evaluateRebootLockReason(
            policy = repo.getCurfewPolicy().copy(isCurfewScheduled = false),
            isDailyExceeded = repo.isDailyLimitExceededToday(),
            access = repo.getAccessState()
        ))
    }

    private fun appLock(
        usedMinutes: Long,
        fg: String = youtube,
        appLimits: Map<String, AppLimit> = limits,
        access: com.shieldchild.app.data.AccessState = repo.getAccessState(now)
    ): LockState = engine.evaluateLockState(
        policy = repo.getCurfewPolicy().copy(isCurfewScheduled = false),
        appLimits = appLimits,
        usageSummary = DailyUsageSummary(totalDeviceUsageMinutes = 0, perAppUsageMinutes = mapOf(youtube to usedMinutes)),
        foregroundPackage = fg,
        calendar = bedtime,
        access = access
    )

    private fun deviceLock(): LockReason? =
        engine.evaluateDeviceLock(repo.getCurfewPolicy(), isDailyLimitReached = false, cal = bedtime, access = repo.getAccessState(now))
}
