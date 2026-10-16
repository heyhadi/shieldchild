package com.shieldchild.app.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

data class DiscoveredApp(
    val packageName: String,
    val appName: String,
    val iconDrawable: Drawable? = null
) {
    fun getIconBitmap(): ImageBitmap? {
        val drawable = iconDrawable ?: return null
        return try {
            val width = drawable.intrinsicWidth.coerceIn(1, 192)
            val height = drawable.intrinsicHeight.coerceIn(1, 192)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
}

class AppDiscoveryManager(private val context: Context) {

    fun getInstalledLaunchableApps(): List<DiscoveredApp> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val activities = pm.queryIntentActivities(mainIntent, 0)
        val selfPkg = context.packageName

        val appMap = mutableMapOf<String, DiscoveredApp>()
        for (resolveInfo in activities) {
            val pkg = resolveInfo.activityInfo.packageName ?: continue
            if (pkg == selfPkg) continue
            if (appMap.containsKey(pkg)) continue

            val label = resolveInfo.loadLabel(pm)?.toString() ?: pkg
            val icon = resolveInfo.loadIcon(pm)

            appMap[pkg] = DiscoveredApp(
                packageName = pkg,
                appName = label,
                iconDrawable = icon
            )
        }

        return appMap.values.sortedBy { it.appName.lowercase() }
    }

    fun getAppIcon(packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
    }
}
