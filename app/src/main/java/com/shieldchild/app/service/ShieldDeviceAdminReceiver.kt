package com.shieldchild.app.service

import com.shieldchild.app.i18n.str
import com.shieldchild.app.R
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.widget.Toast

class ShieldDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, str(R.string.shieldchild_device_admin_activated), Toast.LENGTH_SHORT).show()
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        applyDeviceOwnerRestrictions(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Warning when someone attempts to deactivate Device Admin
        return str(R.string.deactivating_shieldchild_requires_parent_master)
    }

    companion object {
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, ShieldDeviceAdminReceiver::class.java)
        }

        /**
         * Applies hardened device restrictions if running as Device Owner (Android Enterprise).
         */
        fun applyDeviceOwnerRestrictions(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                ?: return
            val admin = getComponentName(context)

            if (dpm.isDeviceOwnerApp(context.packageName)) {
                // Prevent app uninstallation
                dpm.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)
                // Prevent booting into Safe Mode
                dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
                // Prevent Force Stop / Clear Cache in Android Settings
                dpm.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL)
                // Prevent switching to Guest account
                dpm.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER)
                dpm.addUserRestriction(admin, UserManager.DISALLOW_USER_SWITCH)
                // Prevent enabling Developer Options / ADB debugging
                dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
                // Prevent tampering with system date and time
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)

                // Configure LockTask (Kiosk) allowed packages
                val kioskPackages = arrayOf(
                    context.packageName,
                    "com.android.server.telecom",
                    "com.google.android.dialer",
                    "com.android.dialer"
                )
                dpm.setLockTaskPackages(admin, kioskPackages)
            }
        }
    }
}
