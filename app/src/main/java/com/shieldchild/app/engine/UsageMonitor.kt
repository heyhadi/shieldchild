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
     * Aggregates screen time from 00:00:00 of the current day until now.
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

        val appUsageMillis = mutableMapOf<String, Long>()
        val appLastResume = mutableMapOf<String, Long>()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue

            // Exclude system launchers, system UI and ShieldChild itself
            if (isSystemPackage(pkg)) continue

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    appLastResume[pkg] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val resumedAt = appLastResume.remove(pkg)
                    if (resumedAt != null && event.timeStamp > resumedAt) {
                        val duration = event.timeStamp - resumedAt
                        appUsageMillis[pkg] = (appUsageMillis[pkg] ?: 0L) + duration
                    }
                }
            }
        }

        // Account for currently active app
        for ((pkg, resumedAt) in appLastResume) {
            if (now > resumedAt) {
                val duration = now - resumedAt
                appUsageMillis[pkg] = (appUsageMillis[pkg] ?: 0L) + duration
            }
        }

        val perAppMinutes = appUsageMillis.mapValues { (_, millis) -> millis / 60000L }
        // The parent's own use during a parent session doesn't count against the child.
        // shortcut: per-app totals still include it; split by app if parents use limited apps a lot.
        val parentMinutes = (context.applicationContext as? com.shieldchild.app.ShieldApplication)
            ?.preferencesRepository?.getParentUsedMinutesToday() ?: 0L
        val totalMinutes = (perAppMinutes.values.sum() - parentMinutes).coerceAtLeast(0L)

        return DailyUsageSummary(
            totalDeviceUsageMinutes = totalMinutes,
            perAppUsageMinutes = perAppMinutes
        )
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

    private fun isSystemPackage(packageName: String): Boolean {
        return packageName == context.packageName ||
                packageName == "com.android.systemui" ||
                packageName == "com.google.android.apps.nexuslauncher" ||
                packageName == "com.android.launcher" ||
                packageName == "com.google.android.inputmethod.latin"
    }
}
