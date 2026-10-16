package com.shieldchild.app.data

import android.content.Context
import android.content.SharedPreferences
import com.shieldchild.app.engine.ParentAuthManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shield_child_secure_prefs", Context.MODE_PRIVATE)

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
    fun saveRecoveryKeyHash(hash: String) {
        prefs.edit().putString(KEY_RECOVERY_KEY_HASH, hash).apply()
    }

    fun getMasterRecoveryKey(): String {
        val existing = prefs.getString(KEY_RECOVERY_KEY_RAW, null)
        if (existing != null) return existing
        val defaultKey = DEFAULT_RECOVERY_KEY
        prefs.edit().putString(KEY_RECOVERY_KEY_RAW, defaultKey).apply()
        return defaultKey
    }

    fun saveMasterRecoveryKey(key: String) {
        prefs.edit().putString(KEY_RECOVERY_KEY_RAW, key.trim().uppercase()).apply()
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

    fun isBiometricEnabled(): Boolean = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, true)
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
            emergencyPhoneNumber = prefs.getString(KEY_EMERGENCY_PHONE, "911") ?: "911"
        )
    }

    fun saveCurfewPolicy(policy: DeviceCurfewPolicy) {
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
        prefs.edit().putBoolean(KEY_INSTANT_LOCKED, locked).apply()
    }

    fun addBonusMinutes(additionalMinutes: Long) {
        val currentBonus = prefs.getLong(KEY_BONUS_MINUTES, 0L)
        prefs.edit().putLong(KEY_BONUS_MINUTES, currentBonus + additionalMinutes).apply()
    }

    fun grantTemporaryCurfewOverride(minutes: Long) {
        val until = System.currentTimeMillis() + (minutes * 60 * 1000L)
        prefs.edit().putLong(KEY_CURFEW_OVERRIDE_UNTIL, until).apply()
    }

    fun isCurfewOverrideActive(): Boolean {
        val until = prefs.getLong(KEY_CURFEW_OVERRIDE_UNTIL, 0L)
        return System.currentTimeMillis() < until
    }

    fun clearCurfewOverride() {
        prefs.edit().remove(KEY_CURFEW_OVERRIDE_UNTIL).apply()
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
                .apply()
        }
    }

    companion object {
        const val DEFAULT_RECOVERY_KEY = "SHLD-9A4F-K82X-7M1P"
        private const val KEY_RECOVERY_KEY_RAW = "key_recovery_key_raw"
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
        private const val KEY_CURFEW_OVERRIDE_UNTIL = "key_curfew_override_until"
    }
}
