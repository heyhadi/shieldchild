package com.shieldchild.app.overlay

import com.shieldchild.app.i18n.str
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.shieldchild.app.R
import com.shieldchild.app.ui.curfew.CurfewLockActivity

class AppShieldOverlayManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var currentLockedPackage: String? = null

    fun isShowing(packageName: String): Boolean {
        return overlayView != null && currentLockedPackage == packageName
    }

    fun showAppLock(packageName: String, appName: String, limitMinutes: Long, usedMinutes: Long, isBlocked: Boolean = false) {
        if (!com.shieldchild.app.engine.ProfileGuard.isScreenInteractiveForProfile(context)) {
            dismiss()
            return
        }
        if (overlayView != null) {
            if (currentLockedPackage == packageName) return // Already showing for this app
            dismiss()
        }

        if (!Settings.canDrawOverlays(context)) return

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // Not focusable: holding input focus kept the launcher from getting it when Home was
            // pressed, so no app switch was seen and the overlay stayed over Home. Touches still
            // land on the overlay; Back goes to the app under it.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // Programmatically build overlay or inflate
        val view = createOverlayView(packageName, appName, limitMinutes, usedMinutes, isBlocked)
        try {
            windowManager.addView(view, layoutParams)
            overlayView = view
            currentLockedPackage = packageName
            isShowing = true
            holdAudioFocus()
        } catch (_: Exception) {}
    }

    fun dismiss() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
        currentLockedPackage = null
        isShowing = false
        audioFocus?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocus = null
    }

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
    private var audioFocus: android.media.AudioFocusRequest? = null

    /** Same as the lock screen: taking media focus pauses the video under the overlay, and keeps it paused. */
    private fun holdAudioFocus() {
        if (audioFocus != null) return
        val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == android.media.AudioManager.AUDIOFOCUS_LOSS) {
                    overlayView?.postDelayed({ audioFocus?.let { audioManager.requestAudioFocus(it) } }, 300L)
                }
            }
            .build()
        audioFocus = request
        audioManager.requestAudioFocus(request)
    }

    companion object {
        /** Read by the accessibility service: this overlay's own window events aren't an app switch. */
        @Volatile
        var isShowing = false
            private set
    }

    private fun createOverlayView(
        packageName: String,
        appName: String,
        limitMinutes: Long,
        usedMinutes: Long,
        isBlocked: Boolean
    ): View {
        val root = android.widget.FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#F50F1411")) // DarkShieldColors.canvas at 96%, same as the lock screen
            isClickable = true
        }

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val titleView = TextView(context).apply {
            text = str(R.string.is_on_pause, appName)
            textSize = 24f
            setTextColor(android.graphics.Color.parseColor("#E3E8E3")) // ink
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        }

        val subtitleView = TextView(context).apply {
            text = if (isBlocked) str(R.string.app_blocked_by_parent) else str(R.string.you_ve_reached_your_min, limitMinutes)
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor("#B4BEB7")) // inkMuted
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 48)
        }

        val btnHome = Button(context).apply {
            text = str(R.string.go_to_home_screen)
            setTextColor(android.graphics.Color.parseColor("#00391F")) // onPrimary
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24f * context.resources.displayMetrics.density
                setColor(android.graphics.Color.parseColor("#8FD3AE")) // primary
            }
            isAllCaps = false
            setOnClickListener {
                dismiss()
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(homeIntent)
            }
        }

        val btnParent = Button(context).apply {
            text = str(R.string.parent_unlock_add_time)
            setTextColor(android.graphics.Color.parseColor("#8FD3AE")) // primary
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isAllCaps = false
            setOnClickListener {
                dismiss()
                val curfewIntent = Intent(context, CurfewLockActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(CurfewLockActivity.EXTRA_LOCKED_PACKAGE, packageName)
                    putExtra(CurfewLockActivity.EXTRA_LOCKED_APP_NAME, appName)
                }
                context.startActivity(curfewIntent)
            }
        }

        container.addView(titleView)
        container.addView(subtitleView)
        container.addView(btnHome)
        // Extra time can't open a blocked app; the parent unblocks it in the dashboard.
        if (!isBlocked) container.addView(btnParent)
        root.addView(container)

        return root
    }
}
