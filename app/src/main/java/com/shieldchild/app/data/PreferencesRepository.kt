package com.shieldchild.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import com.shieldchild.app.engine.ParentAuthManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PreferencesRepository(
    context: Context,
    /** Uptime clock; injectable so session timing can be unit-tested. */
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }
) {

    private val appContext: Context = context

    private val storageContext: Context = run {
        try {
            val dpContext = context.createDeviceProtectedStorageContext()
            if (dpContext != null) {
                val um = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
                if (um?.isUserUnlocked == true) {
                    try {
                        dpContext.moveSharedPreferencesFrom(context, PREFS_NAME)
                    } catch (_: Exception) {}
                }
                dpContext
            } else {
                context
            }
        } catch (_: Throwable) {
            context
        }
    }

    private val prefs: SharedPreferences =
        storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        removeLegacySharedRecoveryKey()
        // v1.0.0 saved "911" as the default number; it's now the parent's phone ("Call parent"),
        // and real emergencies use the system emergency dialer.
        if (prefs.getString(KEY_EMERGENCY_PHONE, null) == "911") prefs.edit().remove(KEY_EMERGENCY_PHONE).apply()
    }

    // --- Authentication & Security ---
    fun getPinHash(): String? = prefs.getString(KEY_PIN_HASH, null)
    fun getPinSalt(): String? = prefs.getString(KEY_PIN_SALT, null)

    fun savePin(hash: String, salt: String) {
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SALT, salt)
            .apply()
    }

    fun getRecoveryKeyHash(): String? = prefs.getString(KEY_RECOVERY_KEY_HASH, null)
    fun getRecoveryKeySalt(): String? = prefs.getString(KEY_RECOVERY_KEY_SALT, null)
    fun saveRecoveryKeyHash(hash: String, salt: String) {
        prefs.edit()
            .putString(KEY_RECOVERY_KEY_HASH, hash)
            .putString(KEY_RECOVERY_KEY_SALT, salt)
            .apply()
    }

    /**
     * v1.0.0 shipped one hard-coded recovery key on every phone. Drop it (and its hash)
     * so the parent is asked to create a private one.
     */
    private fun removeLegacySharedRecoveryKey() {
        if (prefs.contains(KEY_RECOVERY_KEY_RAW) || (prefs.contains(KEY_RECOVERY_KEY_HASH) && !prefs.contains(KEY_RECOVERY_KEY_SALT))) {
            prefs.edit().remove(KEY_RECOVERY_KEY_RAW).remove(KEY_RECOVERY_KEY_HASH).apply()
        }
    }

    fun hasPinConfigured(): Boolean = getPinHash() != null

    fun getFailedAttempts(): Int = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
    fun setFailedAttempts(count: Int) {
        prefs.edit().putInt(KEY_FAILED_ATTEMPTS, count).apply()
    }

    fun getLockoutUntilTimestamp(): Long = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
    fun setLockoutUntilTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_LOCKOUT_UNTIL, timestamp).apply()
    }

    // --- Kid Profile & Setup Wizard ---
    fun getChildName(): String = prefs.getString(KEY_CHILD_NAME, "Leo") ?: "Leo"
    fun saveChildName(name: String) {
        val trimmed = name.trim().ifEmpty { "Child" }
        prefs.edit().putString(KEY_CHILD_NAME, trimmed).apply()
    }

    fun isSetupCompleted(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETED, false)
    fun setSetupCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply()
    }

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply()
    }

    // --- Policy Settings ---
    fun getCurfewPolicy(): DeviceCurfewPolicy {
        resetDailyCountersIfNewDay()
        val defaultDays = setOf("Sat", "Sun")
        return DeviceCurfewPolicy(
            isDailyLimitEnabled = prefs.getBoolean(KEY_DAILY_LIMIT_ENABLED, true),
            dailyTotalLimitMinutes = prefs.getLong(KEY_DAILY_LIMIT, 120L),
            unlimitedDays = prefs.getStringSet(KEY_UNLIMITED_DAYS, defaultDays) ?: defaultDays,
            isCurfewScheduled = prefs.getBoolean(KEY_CURFEW_SCHEDULED, true),
            curfewStartHour = prefs.getInt(KEY_CURFEW_START_H, 21),
            curfewStartMinute = prefs.getInt(KEY_CURFEW_START_M, 0),
            curfewEndHour = prefs.getInt(KEY_CURFEW_END_H, 7),
            curfewEndMinute = prefs.getInt(KEY_CURFEW_END_M, 0),
            isInstantLocked = prefs.getBoolean(KEY_INSTANT_LOCKED, false),
            bonusMinutesToday = prefs.getLong(KEY_BONUS_MINUTES, 0L),
            emergencyPhoneNumber = prefs.getString(KEY_EMERGENCY_PHONE, "") ?: ""
        )
    }

    fun saveCurfewPolicy(policy: DeviceCurfewPolicy) {
        if (policy.isInstantLocked) {
            endParentSession()
        }
        prefs.edit()
            .putBoolean(KEY_DAILY_LIMIT_ENABLED, policy.isDailyLimitEnabled)
            .putLong(KEY_DAILY_LIMIT, policy.dailyTotalLimitMinutes)
            .putStringSet(KEY_UNLIMITED_DAYS, policy.unlimitedDays)
            .putBoolean(KEY_CURFEW_SCHEDULED, policy.isCurfewScheduled)
            .putInt(KEY_CURFEW_START_H, policy.curfewStartHour)
            .putInt(KEY_CURFEW_START_M, policy.curfewStartMinute)
            .putInt(KEY_CURFEW_END_H, policy.curfewEndHour)
            .putInt(KEY_CURFEW_END_M, policy.curfewEndMinute)
            .putBoolean(KEY_INSTANT_LOCKED, policy.isInstantLocked)
            .putLong(KEY_BONUS_MINUTES, policy.bonusMinutesToday)
            .putString(KEY_EMERGENCY_PHONE, policy.emergencyPhoneNumber)
            .apply()
    }

    fun setInstantLock(locked: Boolean) {
        if (locked) {
            endParentSession()
        }
        prefs.edit().putBoolean(KEY_INSTANT_LOCKED, locked).apply()
    }

    fun addBonusMinutes(additionalMinutes: Long) {
        val currentBonus = prefs.getLong(KEY_BONUS_MINUTES, 0L)
        prefs.edit().putLong(KEY_BONUS_MINUTES, currentBonus + additionalMinutes).apply()
    }

    // --- Parent grants & parent session ---
    fun getAccessState(now: Long = System.currentTimeMillis()): AccessState {
        return AccessState(
            isBedtimePaused = now < prefs.getLong(KEY_BEDTIME_PAUSED_UNTIL, 0L),
            isLimitPaused = now < prefs.getLong(KEY_LIMIT_PAUSED_UNTIL, 0L),
            isParentSession = isParentSessionActive(),
            pausedApps = getPausedApps(now)
        )
    }

    fun getBedtimePausedUntil(): Long = prefs.getLong(KEY_BEDTIME_PAUSED_UNTIL, 0L)
    fun getLimitPausedUntil(): Long = prefs.getLong(KEY_LIMIT_PAUSED_UNTIL, 0L)

    fun pauseBedtimeUntil(timestamp: Long) {
        prefs.edit().putLong(KEY_BEDTIME_PAUSED_UNTIL, timestamp).apply()
    }

    fun pauseLimitUntil(timestamp: Long) {
        prefs.edit().putLong(KEY_LIMIT_PAUSED_UNTIL, timestamp).apply()
    }

    fun pauseAppLimitUntil(packageName: String, timestamp: Long) {
        val currentJson = prefs.getString(KEY_PAUSED_APPS_JSON, null)
        val jsonObject = if (currentJson != null) {
            try { JSONObject(currentJson) } catch (_: Exception) { JSONObject() }
        } else {
            JSONObject()
        }
        jsonObject.put(packageName, timestamp)
        prefs.edit().putString(KEY_PAUSED_APPS_JSON, jsonObject.toString()).apply()
    }

    fun getPausedApps(now: Long = System.currentTimeMillis()): Set<String> {
        val currentJson = prefs.getString(KEY_PAUSED_APPS_JSON, null) ?: return emptySet()
        val jsonObject = try { JSONObject(currentJson) } catch (_: Exception) { return emptySet() }
        val paused = mutableSetOf<String>()
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val pkg = keys.next()
            val until = jsonObject.optLong(pkg, 0L)
            if (now < until) {
                paused.add(pkg)
            }
        }
        return paused
    }

    fun getClockAnchor(): Triple<Long, Long, Int> {
        val wall = prefs.getLong(KEY_CLOCK_ANCHOR_WALL, 0L)
        val uptime = prefs.getLong(KEY_CLOCK_ANCHOR_UPTIME, 0L)
        val boot = prefs.getInt(KEY_CLOCK_ANCHOR_BOOT, -1)
        return Triple(wall, uptime, boot)
    }

    fun saveClockAnchor(wallMs: Long, uptimeMs: Long, bootCount: Int) {
        prefs.edit()
            .putLong(KEY_CLOCK_ANCHOR_WALL, wallMs)
            .putLong(KEY_CLOCK_ANCHOR_UPTIME, uptimeMs)
            .putInt(KEY_CLOCK_ANCHOR_BOOT, bootCount)
            .apply()
    }

    fun getBootCount(): Int = bootCount()

    /**
     * Parent sessions are timed with elapsedRealtime plus the boot count, so changing the
     * clock can't stretch one and a reboot always ends it.
     */
    fun startParentSession() {
        val now = elapsedRealtime()
        prefs.edit()
            .putLong(KEY_PARENT_SESSION_START, now)
            .putLong(KEY_PARENT_SESSION_TOUCH, now)
            .putInt(KEY_PARENT_SESSION_BOOT, bootCount())
            .commit()
    }

    fun hasParentSessionRecord(): Boolean = prefs.getLong(KEY_PARENT_SESSION_START, 0L) > 0L

    fun isParentSessionActive(): Boolean {
        val start = prefs.getLong(KEY_PARENT_SESSION_START, 0L)
        if (start <= 0L || prefs.getInt(KEY_PARENT_SESSION_BOOT, -1) != bootCount()) return false
        val now = elapsedRealtime()
        if (now < start) return false
        // Never cut off a parent's phone call.
        // shortcut: a child who starts a call inside the session also keeps it open while the call lasts;
        // restrict to the dialer being in front if that turns out to be abused.
        if (isInCall()) return true
        val lastTouch = prefs.getLong(KEY_PARENT_SESSION_TOUCH, start)
        return now - start < PARENT_SESSION_MAX_MS && now - lastTouch < PARENT_SESSION_IDLE_MS
    }

    /** Called on user interaction; throttled so scrolling doesn't hammer storage. */
    fun touchParentSession() {
        if (!isParentSessionActive()) return
        val now = elapsedRealtime()
        if (now - prefs.getLong(KEY_PARENT_SESSION_TOUCH, 0L) > 15_000L) {
            prefs.edit().putLong(KEY_PARENT_SESSION_TOUCH, now).apply()
        }
    }

    /** Ends the session and keeps the parent's screen time out of the child's total. */
    fun endParentSession() {
        val start = prefs.getLong(KEY_PARENT_SESSION_START, 0L)
        if (start <= 0L) return
        val sameBoot = prefs.getInt(KEY_PARENT_SESSION_BOOT, -1) == bootCount()
        val now = elapsedRealtime()
        val usedMs = if (sameBoot && now >= start) now - start else 0L
        resetDailyCountersIfNewDay()
        prefs.edit()
            .putLong(KEY_PARENT_EXCLUDED_MS, prefs.getLong(KEY_PARENT_EXCLUDED_MS, 0L) + usedMs)
            .remove(KEY_PARENT_SESSION_START)
            .remove(KEY_PARENT_SESSION_TOUCH)
            .remove(KEY_PARENT_SESSION_BOOT)
            .commit()
    }

    /** Screen time used by the parent today (finished and ongoing sessions), not the child. */
    fun getParentUsedMinutesToday(): Long {
        resetDailyCountersIfNewDay()
        val start = prefs.getLong(KEY_PARENT_SESSION_START, 0L)
        val ongoing = if (isParentSessionActive()) elapsedRealtime() - start else 0L
        return (prefs.getLong(KEY_PARENT_EXCLUDED_MS, 0L) + ongoing) / 60_000L
    }

    private fun isInCall(): Boolean {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return false
        return audio.mode == android.media.AudioManager.MODE_IN_CALL
    }

    private fun bootCount(): Int = try {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Exception) {
        0
    }

    // --- Remote unlock code (TOTP secret is stored encrypted, never in plain text) ---
    fun getRemoteSecretEncrypted(): String? = prefs.getString(KEY_REMOTE_SECRET, null)
    fun saveRemoteSecretEncrypted(value: String?) {
        prefs.edit().apply { if (value == null) remove(KEY_REMOTE_SECRET) else putString(KEY_REMOTE_SECRET, value) }
            .putLong(KEY_REMOTE_LAST_STEP, 0L)
            .apply()
    }

    fun getRemoteLastStep(): Long = prefs.getLong(KEY_REMOTE_LAST_STEP, 0L)
    fun setRemoteLastStep(step: Long) {
        prefs.edit().putLong(KEY_REMOTE_LAST_STEP, step).commit()
    }

    fun getRemoteCodeMinutes(): Long = prefs.getLong(KEY_REMOTE_MINUTES, 15L)
    fun setRemoteCodeMinutes(minutes: Long) {
        prefs.edit().putLong(KEY_REMOTE_MINUTES, minutes).apply()
    }

    fun getRemoteUnlocksToday(): Int {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return if (prefs.getString(KEY_REMOTE_COUNT_DAY, "") == today) prefs.getInt(KEY_REMOTE_COUNT, 0) else 0
    }

    fun incrementRemoteUnlocksToday() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        prefs.edit()
            .putString(KEY_REMOTE_COUNT_DAY, today)
            .putInt(KEY_REMOTE_COUNT, getRemoteUnlocksToday() + 1)
            .apply()
    }

    // --- Appearance ---
    fun getThemeMode(): String = prefs.getString(KEY_THEME_MODE, "SYSTEM") ?: "SYSTEM"
    fun setThemeMode(mode: String) {
        prefs.edit().putString(KEY_THEME_MODE, mode).apply()
    }

    fun getLanguage(): String = prefs.getString(KEY_LANGUAGE, "SYSTEM") ?: "SYSTEM"
    fun setLanguage(language: String) {
        prefs.edit().putString(KEY_LANGUAGE, language).commit()
    }

    // --- Grant log (shown to the parent in Security) ---
    fun addGrantLog(method: GrantMethod, description: String) {
        val array = try {
            JSONArray(prefs.getString(KEY_GRANT_LOG, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        array.put(JSONObject().apply {
            put("t", System.currentTimeMillis())
            put("m", method.name)
            put("d", description)
        })
        val start = (array.length() - MAX_GRANT_LOG).coerceAtLeast(0)
        val trimmed = JSONArray()
        for (i in start until array.length()) trimmed.put(array.get(i))
        prefs.edit().putString(KEY_GRANT_LOG, trimmed.toString()).apply()
    }

    /** Newest first. */
    fun getGrantLog(): List<GrantLogEntry> = try {
        val array = JSONArray(prefs.getString(KEY_GRANT_LOG, "[]"))
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            GrantLogEntry(o.getLong("t"), GrantMethod.valueOf(o.getString("m")), o.getString("d"))
        }.reversed()
    } catch (_: Exception) {
        emptyList()
    }

    // --- Per-App Limits ---
    fun getAppLimits(): Map<String, AppLimit> {
        val jsonString = prefs.getString(KEY_APP_LIMITS_JSON, "[]") ?: "[]"
        val limits = mutableMapOf<String, AppLimit>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val pkg = obj.getString("packageName")
                val name = obj.optString("appName", pkg)
                val limit = obj.getLong("dailyLimitMinutes")
                val blocked = obj.optBoolean("isBlocked", false)
                limits[pkg] = AppLimit(pkg, name, limit, blocked)
            }
        } catch (_: Exception) {}
        return limits
    }

    fun saveAppLimits(limits: Collection<AppLimit>) {
        val array = JSONArray()
        for (limit in limits) {
            val obj = JSONObject().apply {
                put("packageName", limit.packageName)
                put("appName", limit.appName)
                put("dailyLimitMinutes", limit.dailyLimitMinutes)
                put("isBlocked", limit.isBlocked)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_APP_LIMITS_JSON, array.toString()).apply()
    }

    fun setAppLimit(limit: AppLimit) {
        val limits = getAppLimits().toMutableMap()
        limits[limit.packageName] = limit
        saveAppLimits(limits.values)
    }

    fun removeAppLimit(packageName: String) {
        val limits = getAppLimits().toMutableMap()
        limits.remove(packageName)
        saveAppLimits(limits.values)
    }

    // --- Daily Limit Exceeded State Persistence (for Reboot Lock Enforcement) ---
    fun isDailyLimitExceededToday(): Boolean {
        resetDailyCountersIfNewDay()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val recordedDay = prefs.getString(KEY_DAILY_LIMIT_EXCEEDED_DAY, "")
        return recordedDay == todayStr
    }

    fun setDailyLimitExceededToday(exceeded: Boolean) {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        prefs.edit().putString(KEY_DAILY_LIMIT_EXCEEDED_DAY, if (exceeded) todayStr else "").apply()
    }

    // --- Daily Reset Helper ---
    private fun resetDailyCountersIfNewDay() {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val lastDayStr = prefs.getString(KEY_LAST_RECORDED_DAY, "")
        if (todayStr != lastDayStr) {
            prefs.edit()
                .putString(KEY_LAST_RECORDED_DAY, todayStr)
                .putLong(KEY_BONUS_MINUTES, 0L)
                .putString(KEY_DAILY_LIMIT_EXCEEDED_DAY, "")
                .putLong(KEY_PARENT_EXCLUDED_MS, 0L)
                .remove(KEY_PAUSED_APPS_JSON)
                .apply()
        }
    }

    // --- Update Checking & Banner Preferences ---
    fun getLastUpdateCheckTime(): Long = prefs.getLong(KEY_LAST_UPDATE_CHECK_TIME, 0L)
    fun setLastUpdateCheckTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_TIME, time).apply()
    }

    fun isUpdateBannerDismissedForBuild(build: Long): Boolean {
        return prefs.getLong(KEY_DISMISSED_UPDATE_BUILD, -1L) == build
    }

    fun dismissUpdateBannerForBuild(build: Long) {
        prefs.edit().putLong(KEY_DISMISSED_UPDATE_BUILD, build).apply()
    }

    companion object {
        private const val PREFS_NAME = "shield_child_secure_prefs"
        private const val KEY_PAUSED_APPS_JSON = "key_paused_apps_json"
        private const val KEY_CLOCK_ANCHOR_WALL = "key_clock_anchor_wall"
        private const val KEY_CLOCK_ANCHOR_UPTIME = "key_clock_anchor_uptime"
        private const val KEY_CLOCK_ANCHOR_BOOT = "key_clock_anchor_boot"
        private const val KEY_RECOVERY_KEY_RAW = "key_recovery_key_raw" // legacy, removed on load
        private const val KEY_RECOVERY_KEY_SALT = "key_recovery_key_salt"
        private const val KEY_PIN_HASH = "key_pin_hash"
        private const val KEY_PIN_SALT = "key_pin_salt"
        private const val KEY_RECOVERY_KEY_HASH = "key_recovery_key_hash"
        private const val KEY_FAILED_ATTEMPTS = "key_failed_attempts"
        private const val KEY_LOCKOUT_UNTIL = "key_lockout_until"

        private const val KEY_CHILD_NAME = "key_child_name"
        private const val KEY_SETUP_COMPLETED = "key_setup_completed"
        private const val KEY_BIOMETRIC_ENABLED = "key_biometric_enabled"

        private const val KEY_DAILY_LIMIT_ENABLED = "key_daily_limit_enabled"
        private const val KEY_DAILY_LIMIT = "key_daily_limit"
        private const val KEY_UNLIMITED_DAYS = "key_unlimited_days"
        private const val KEY_CURFEW_SCHEDULED = "key_curfew_scheduled"
        private const val KEY_CURFEW_START_H = "key_curfew_start_h"
        private const val KEY_CURFEW_START_M = "key_curfew_start_m"
        private const val KEY_CURFEW_END_H = "key_curfew_end_h"
        private const val KEY_CURFEW_END_M = "key_curfew_end_m"
        private const val KEY_INSTANT_LOCKED = "key_instant_locked"
        private const val KEY_BONUS_MINUTES = "key_bonus_minutes"
        private const val KEY_EMERGENCY_PHONE = "key_emergency_phone"
        private const val KEY_APP_LIMITS_JSON = "key_app_limits_json"
        private const val KEY_LAST_RECORDED_DAY = "key_last_recorded_day"
        private const val KEY_DAILY_LIMIT_EXCEEDED_DAY = "key_daily_limit_exceeded_day"
        private const val KEY_BEDTIME_PAUSED_UNTIL = "key_bedtime_paused_until"
        private const val KEY_LIMIT_PAUSED_UNTIL = "key_limit_paused_until"
        private const val KEY_PARENT_SESSION_START = "key_parent_session_start"
        private const val KEY_PARENT_SESSION_TOUCH = "key_parent_session_touch"
        private const val KEY_PARENT_SESSION_BOOT = "key_parent_session_boot"
        private const val KEY_PARENT_EXCLUDED_MS = "key_parent_excluded_ms"
        private const val KEY_GRANT_LOG = "key_grant_log"
        private const val KEY_THEME_MODE = "key_theme_mode"
        private const val KEY_LANGUAGE = "key_language"
        private const val KEY_REMOTE_SECRET = "key_remote_secret"
        private const val KEY_REMOTE_LAST_STEP = "key_remote_last_step"
        private const val KEY_REMOTE_MINUTES = "key_remote_minutes"
        private const val KEY_REMOTE_COUNT = "key_remote_count"
        private const val KEY_REMOTE_COUNT_DAY = "key_remote_count_day"
        private const val KEY_LAST_UPDATE_CHECK_TIME = "key_last_update_check_time"
        private const val KEY_DISMISSED_UPDATE_BUILD = "key_dismissed_update_build"
        private const val MAX_GRANT_LOG = 50

        // Hard end even if someone keeps touching: a child handed the phone gets at most this.
        const val PARENT_SESSION_MAX_MS = 10 * 60_000L
        const val PARENT_SESSION_IDLE_MS = 5 * 60_000L
    }
}
