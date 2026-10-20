package com.shieldchild.app.engine

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.Calendar

data class DailyUsageSummary(
    val totalDeviceUsageMinutes: Long,
    val perAppUsageMinutes: Map<String, Long>
)

class UsageMonitor(private val context: Context) {

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    /**
     * Screen time from 00:00 today until now. Per-app totals are kept in milliseconds and only
     * turned into minutes at the end, so many short sessions still add up.
     */
    fun queryDailyUsage(): DailyUsageSummary {
        val usm = usageStatsManager ?: return DailyUsageSummary(0L, emptyMap())

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        val now = System.currentTimeMillis()

        val events = usm.queryEvents(startOfDay, now)
        val event = UsageEvents.Event()
        val list = ArrayList<UsageEventLite>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            list.add(UsageEventLite(pkg, event.className ?: "", event.eventType, event.timeStamp))
        }

        val excluded = excludedPackages()
        val onScreen = visibleIntervals(list, startOfDay, now) { it in excluded }
        val perAppMillis = perApp(onScreen)
        val parentMinutes = (context.applicationContext as? com.shieldchild.app.ShieldApplication)
            ?.preferencesRepository?.getParentUsedMinutesToday() ?: 0L
        // Device time is time with any app on screen, so a PiP over another app isn't counted twice.
        // The parent's own use during a parent session doesn't count against the child.
        // shortcut: per-app totals still include it; split by app if parents use limited apps a lot.
        val totalMinutes = (screenMillis(onScreen) / 60_000L - parentMinutes).coerceAtLeast(0L)

        return DailyUsageSummary(
            totalDeviceUsageMinutes = totalMinutes,
            perAppUsageMinutes = perAppMillis.mapValues { (_, millis) -> millis / 60_000L }
        )
    }

    /** This app, System UI, the home screen (whichever launcher this phone uses) and keyboards. */
    private fun excludedPackages(): Set<String> {
        val pm = context.packageManager
        val home = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), 0
        ).map { it.activityInfo.packageName }
        val keyboards = (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.enabledInputMethodList?.map { it.packageName }.orEmpty()
        return setOf(context.packageName, "com.android.systemui") + home + keyboards
    }

    /**
     * Determines the currently active foreground package name.
     */
    fun getForegroundPackageName(): String? {
        val usm = usageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10000L, now)
        val event = UsageEvents.Event()
        var topPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                topPackage = event.packageName
            }
        }
        return topPackage
    }

    companion object {
        /** One stretch of time an app was on screen. */
        data class Interval(val packageName: String, val start: Long, val end: Long)

        /**
         * When each app was on screen today. An app is on screen from an activity resuming (or a
         * paused one showing again, e.g. its picture-in-picture after the screen comes back on) until
         * its last visible activity stops or the screen turns off. Billing until *stopped*, not
         * paused, is what counts picture-in-picture: Android pauses an activity when it shrinks to
         * PiP and only stops it when the PiP closes. Several screens of one app count once.
         * Only an app whose first event today is a pause or stop was open before midnight: a later
         * stray pause (a screen that closes before it resumes, e.g. the package installer) must not
         * be billed back to 00:00.
         */
        fun visibleIntervals(
            events: List<UsageEventLite>,
            startOfDay: Long,
            now: Long,
            isExcluded: (String) -> Boolean
        ): List<Interval> {
            val result = mutableListOf<Interval>()
            val visible = mutableMapOf<String, MutableSet<String>>()
            val since = mutableMapOf<String, Long>()
            val seen = mutableSetOf<String>()
            fun close(pkg: String, at: Long) {
                val start = since.remove(pkg) ?: return
                if (at > start) result.add(Interval(pkg, start, at))
            }
            for (e in events) {
                if (e.type == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                    for (pkg in since.keys.toList()) close(pkg, e.time)
                    visible.clear()
                    continue
                }
                if (isExcluded(e.packageName)) continue
                val pkg = e.packageName
                val open = visible.getOrPut(pkg) { mutableSetOf() }
                val firstToday = pkg !in seen
                seen.add(pkg)
                when (e.type) {
                    UsageEvents.Event.ACTIVITY_RESUMED, UsageEvents.Event.ACTIVITY_PAUSED -> {
                        if (open.isEmpty()) {
                            since[pkg] = if (firstToday && e.type == UsageEvents.Event.ACTIVITY_PAUSED) startOfDay else e.time
                        }
                        open.add(e.className)
                    }
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (open.remove(e.className)) {
                            if (open.isEmpty()) close(pkg, e.time)
                        } else if (firstToday) {
                            if (e.time > startOfDay) result.add(Interval(pkg, startOfDay, e.time))
                        }
                    }
                }
            }
            for (pkg in since.keys.toList()) close(pkg, now)
            return result
        }

        /** On-screen milliseconds per package. */
        fun perApp(intervals: List<Interval>): Map<String, Long> =
            intervals.groupBy { it.packageName }.mapValues { (_, list) -> list.sumOf { it.end - it.start } }

        /** Milliseconds with at least one app on screen; overlapping apps (PiP) count once. */
        fun screenMillis(intervals: List<Interval>): Long {
            var total = 0L
            var coveredUntil = Long.MIN_VALUE
            for (i in intervals.sortedBy { it.start }) {
                val from = maxOf(i.start, coveredUntil)
                if (i.end > from) total += i.end - from
                coveredUntil = maxOf(coveredUntil, i.end)
            }
            return total
        }

        fun aggregate(
            events: List<UsageEventLite>,
            startOfDay: Long,
            now: Long,
            isExcluded: (String) -> Boolean
        ): Map<String, Long> = perApp(visibleIntervals(events, startOfDay, now, isExcluded))
    }
}

data class UsageEventLite(val packageName: String, val className: String, val type: Int, val time: Long)
