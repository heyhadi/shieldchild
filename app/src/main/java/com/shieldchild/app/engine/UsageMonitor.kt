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
        val perAppMillis = aggregate(list, startOfDay, now) { it in excluded }
        val parentMinutes = (context.applicationContext as? com.shieldchild.app.ShieldApplication)
            ?.preferencesRepository?.getParentUsedMinutesToday() ?: 0L
        // The parent's own use during a parent session doesn't count against the child.
        // shortcut: per-app totals still include it; split by app if parents use limited apps a lot.
        val totalMinutes = (perAppMillis.values.sum() / 60_000L - parentMinutes).coerceAtLeast(0L)

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
        /**
         * Foreground time per package in milliseconds. An app counts as in front from its first
         * activity resuming until its last one pauses, so apps with several screens aren't
         * double-counted. A pause with no resume today means the app was opened before midnight.
         */
        fun aggregate(
            events: List<UsageEventLite>,
            startOfDay: Long,
            now: Long,
            isExcluded: (String) -> Boolean
        ): Map<String, Long> {
            val totals = mutableMapOf<String, Long>()
            val resumed = mutableMapOf<String, MutableSet<String>>()
            val since = mutableMapOf<String, Long>()
            val seen = mutableSetOf<String>()
            fun add(pkg: String, millis: Long) {
                if (millis > 0) totals[pkg] = (totals[pkg] ?: 0L) + millis
            }
            for (e in events) {
                if (isExcluded(e.packageName)) continue
                val key = e.packageName + "/" + e.className
                val open = resumed.getOrPut(e.packageName) { mutableSetOf() }
                when (e.type) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        if (open.isEmpty()) since[e.packageName] = e.time
                        open.add(e.className)
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                        if (open.remove(e.className)) {
                            if (open.isEmpty()) add(e.packageName, e.time - (since.remove(e.packageName) ?: e.time))
                        } else if (key !in seen && open.isEmpty()) {
                            add(e.packageName, e.time - startOfDay)
                        }
                    }
                }
                seen.add(key)
            }
            for ((pkg, start) in since) {
                if (resumed[pkg].orEmpty().isNotEmpty()) add(pkg, now - start)
            }
            return totals
        }
    }
}

data class UsageEventLite(val packageName: String, val className: String, val type: Int, val time: Long)
