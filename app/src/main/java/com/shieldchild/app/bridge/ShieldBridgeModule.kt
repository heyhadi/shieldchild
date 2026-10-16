package com.shieldchild.app.bridge

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.shieldchild.app.ShieldApplication
import com.shieldchild.app.data.AppLimit
import com.shieldchild.app.data.DeviceCurfewPolicy
import com.shieldchild.app.engine.AuthResult
import com.shieldchild.app.engine.ParentAuthManager
import com.shieldchild.app.engine.UsageMonitor
import com.shieldchild.app.service.ShieldDeviceAdminReceiver
import com.shieldchild.app.service.ShieldMonitorService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Bridge Module connecting UI layer to Android Core Enforcement.
 * Handles inter-process communication and policy enforcement hooks.
 */
class ShieldBridgeModule(private val context: Context) {

    private val repository = ShieldApplication.instance.preferencesRepository
    private val authManager = ParentAuthManager(repository)
    private val usageMonitor = UsageMonitor(context)

    fun getDailyUsage(): String {
        val summary = usageMonitor.queryDailyUsage()
        val json = JSONObject().apply {
            put("totalMinutes", summary.totalDeviceUsageMinutes)
            val appsObj = JSONObject()
            summary.perAppUsageMinutes.forEach { (pkg, mins) ->
                appsObj.put(pkg, mins)
            }
            put("perApp", appsObj)
        }
        return json.toString()
    }

    fun getCurfewPolicy(): String {
        val p = repository.getCurfewPolicy()
        return JSONObject().apply {
            put("dailyTotalLimitMinutes", p.dailyTotalLimitMinutes)
            put("effectiveLimitMinutes", p.effectiveDailyLimitMinutes)
            put("isCurfewScheduled", p.isCurfewScheduled)
            put("curfewStartHour", p.curfewStartHour)
            put("curfewStartMinute", p.curfewStartMinute)
            put("curfewEndHour", p.curfewEndHour)
            put("curfewEndMinute", p.curfewEndMinute)
            put("isInstantLocked", p.isInstantLocked)
            put("bonusMinutesToday", p.bonusMinutesToday)
            put("emergencyPhoneNumber", p.emergencyPhoneNumber)
        }.toString()
    }

    fun setDailyLimit(minutes: Long): Boolean {
        val current = repository.getCurfewPolicy()
        repository.saveCurfewPolicy(current.copy(dailyTotalLimitMinutes = minutes))
        notifyPolicyChange()
        return true
    }

    fun setCurfewSchedule(startHour: Int, startMinute: Int, endHour: Int, endMinute: Int): Boolean {
        val current = repository.getCurfewPolicy()
        repository.saveCurfewPolicy(
            current.copy(
                isCurfewScheduled = true,
                curfewStartHour = startHour,
                curfewStartMinute = startMinute,
                curfewEndHour = endHour,
                curfewEndMinute = endMinute
            )
        )
        notifyPolicyChange()
        return true
    }

    fun triggerInstantLock(locked: Boolean): Boolean {
        repository.setInstantLock(locked)
        notifyPolicyChange()
        return true
    }

    fun addBonusMinutes(additionalMinutes: Long): Boolean {
        repository.addBonusMinutes(additionalMinutes)
        notifyPolicyChange()
        return true
    }

    fun setAppLimit(packageName: String, appName: String, limitMinutes: Long, isBlocked: Boolean): Boolean {
        repository.setAppLimit(AppLimit(packageName, appName, limitMinutes, isBlocked))
        notifyPolicyChange()
        return true
    }

    fun removeAppLimit(packageName: String): Boolean {
        repository.removeAppLimit(packageName)
        notifyPolicyChange()
        return true
    }

    fun getAppLimits(): String {
        val limits = repository.getAppLimits().values
        val array = JSONArray()
        for (limit in limits) {
            array.put(JSONObject().apply {
                put("packageName", limit.packageName)
                put("appName", limit.appName)
                put("dailyLimitMinutes", limit.dailyLimitMinutes)
                put("isBlocked", limit.isBlocked)
            })
        }
        return array.toString()
    }

    fun verifyMasterPin(pin: String): String {
        val result = authManager.verifyPin(pin)
        return JSONObject().apply {
            when (result) {
                is AuthResult.Success -> {
                    put("success", true)
                }
                is AuthResult.InvalidPin -> {
                    put("success", false)
                    put("remainingAttempts", result.remainingAttempts)
                }
                is AuthResult.LockedOut -> {
                    put("success", false)
                    put("lockedOutSeconds", result.remainingSeconds)
                }
            }
        }.toString()
    }

    fun setMasterPin(newPin: String): Boolean {
        authManager.setMasterPin(newPin)
        return true
    }

    fun checkPermissionsStatus(): String {
        val hasUsage = checkUsageStatsPermission()
        val hasOverlay = Settings.canDrawOverlays(context)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val hasAdmin = dpm?.isAdminActive(ShieldDeviceAdminReceiver.getComponentName(context)) ?: false
        val isDeviceOwner = dpm?.isDeviceOwnerApp(context.packageName) ?: false

        return JSONObject().apply {
            put("usageStats", hasUsage)
            put("overlay", hasOverlay)
            put("deviceAdmin", hasAdmin)
            put("deviceOwner", isDeviceOwner)
        }.toString()
    }

    private fun checkUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun notifyPolicyChange() {
        val intent = Intent(context, ShieldMonitorService::class.java).apply {
            action = ShieldMonitorService.ACTION_RECHECK_POLICIES
        }
        context.startService(intent)
    }
}
