package com.shieldchild.app.engine

import android.content.Context
import com.shieldchild.app.data.AccessState
import com.shieldchild.app.data.AppLimit
import com.shieldchild.app.data.DeviceCurfewPolicy
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.data.LockState
import com.shieldchild.app.data.PreferencesRepository
import java.util.Calendar

/** Everything one lock decision reads, taken at one moment. */
data class LockSnapshot(
    val policy: DeviceCurfewPolicy,
    val appLimits: Map<String, AppLimit>,
    val usage: DailyUsageSummary,
    val foregroundPackage: String?,
    val access: AccessState,
    val calendar: Calendar
)

/** What an open lock screen should do after a re-check. */
sealed class LockScreenUpdate {
    object Close : LockScreenUpdate()
    object Keep : LockScreenUpdate()
    data class ShowDeviceLock(val reason: LockReason) : LockScreenUpdate()
}

/**
 * The one place that decides whether to lock (docs/LOCK_RULES.md). The monitor service, the
 * accessibility service, the lock screen, the dashboard, boot and app start all ask it, with a
 * snapshot from [snapshot], so they can't disagree about the same moment.
 */
object LockDecider {

    private val engine = PolicyEngine()

    fun decide(s: LockSnapshot): LockState =
        engine.evaluateLockState(s.policy, s.appLimits, s.usage, s.foregroundPackage, s.calendar, s.access)

    /** The whole-phone lock, whatever app is in front. */
    fun deviceLock(s: LockSnapshot): LockReason? =
        (decide(s.copy(foregroundPackage = null)) as? LockState.CurfewLock)?.reason

    /** Whether [pkg] alone is locked (blocked or out of time) while the phone itself is open. */
    fun isAppLocked(s: LockSnapshot, pkg: String): Boolean =
        decide(s.copy(foregroundPackage = pkg)) is LockState.SingleAppLock

    /**
     * For the lock screen's re-check. [lockedPackage] is the app it was opened for, or null for a
     * whole-phone lock. It stays open while the parent is choosing what to give.
     */
    fun lockScreenUpdate(s: LockSnapshot, lockedPackage: String?, parentChoosing: Boolean): LockScreenUpdate =
        when (val state = decide(s.copy(foregroundPackage = lockedPackage))) {
            LockState.Unlocked -> if (parentChoosing) LockScreenUpdate.Keep else LockScreenUpdate.Close
            is LockState.CurfewLock -> LockScreenUpdate.ShowDeviceLock(state.reason)
            is LockState.SingleAppLock -> LockScreenUpdate.Keep
        }

    fun snapshot(
        repo: PreferencesRepository,
        usage: DailyUsageSummary,
        foregroundPackage: String? = null,
        now: Long = System.currentTimeMillis()
    ): LockSnapshot = LockSnapshot(
        policy = repo.getCurfewPolicy(),
        appLimits = repo.getAppLimits(),
        usage = usage,
        foregroundPackage = foregroundPackage,
        access = repo.getAccessState(now),
        calendar = Calendar.getInstance().apply { timeInMillis = now }
    )

    /** Snapshot with the latest usage the monitor service measured; see [usage]. */
    fun snapshot(context: Context, repo: PreferencesRepository, foregroundPackage: String? = null): LockSnapshot {
        val now = System.currentTimeMillis()
        return snapshot(repo, usage(repo, now) { UsageMonitor(context).queryDailyUsage() }, foregroundPackage, now)
    }

    /**
     * For boot and app start, on the main thread before the monitor service runs: the shared usage
     * if there is any, else the total saved at the last check, without reading usage events.
     */
    fun startupSnapshot(repo: PreferencesRepository, now: Long = System.currentTimeMillis()): LockSnapshot =
        snapshot(repo, freshCached(now) ?: DailyUsageSummary(repo.getSavedUsageMinutesToday(now), emptyMap()), null, now)

    // --- Usage, shared by everyone. Querying it means reading all of today's usage events, too slow
    // for every app switch, so the monitor service refreshes it every few seconds and others reuse it.

    private class CachedUsage(val usage: DailyUsageSummary, val dayStart: Long, val takenAt: Long)

    @Volatile
    private var cached: CachedUsage? = null

    const val USAGE_MAX_AGE_MS = 30_000L

    /** Measures usage now and shares it. Called by the monitor service on every check. */
    fun refreshUsage(repo: PreferencesRepository, now: Long, query: () -> DailyUsageSummary): DailyUsageSummary {
        val live = try { query() } catch (_: Exception) { DailyUsageSummary(0L, emptyMap()) }
        // Usage only grows during a day. Before the phone is unlocked after a reboot usage stats
        // can't be read yet; the saved total keeps the daily limit locked then.
        val saved = repo.getSavedUsageMinutesToday(now)
        val usage = if (saved > live.totalDeviceUsageMinutes) live.copy(totalDeviceUsageMinutes = saved) else live
        if (usage.totalDeviceUsageMinutes != saved) repo.saveUsageMinutesToday(usage.totalDeviceUsageMinutes, now)
        cached = CachedUsage(usage, startOfDay(now), now)
        return usage
    }

    /** The shared usage if it's from the last [USAGE_MAX_AGE_MS] today, otherwise measured now. */
    fun usage(repo: PreferencesRepository, now: Long, query: () -> DailyUsageSummary): DailyUsageSummary =
        freshCached(now) ?: refreshUsage(repo, now, query)

    private fun freshCached(now: Long): DailyUsageSummary? {
        val c = cached ?: return null
        return c.usage.takeIf { c.dayStart == startOfDay(now) && now - c.takenAt in 0..USAGE_MAX_AGE_MS }
    }

    internal fun clearCachedUsage() {
        cached = null
    }

    private fun startOfDay(now: Long): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
