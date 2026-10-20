package com.shieldchild.app.engine

/** An app window on screen while the launcher has focus. */
data class ScreenWindow(val packageName: String, val isPictureInPicture: Boolean)

/** Which app to report as in front, and which picture-in-picture app to close. */
data class HomeDecision(val reportedPackage: String, val closePipPackage: String?)

object HomeWindows {
    /**
     * Decides what Home in front means. A limited app beside Home (split screen) is reported so
     * its lock covers it. A limited app shrunk to picture-in-picture is not: the overlay would
     * cover Home and "Go to Home Screen" would land back on it. It is closed instead when locked.
     */
    fun decide(
        launcherPackage: String,
        windows: List<ScreenWindow>,
        limitedApps: Set<String>,
        isLocked: (String) -> Boolean
    ): HomeDecision {
        val limited = windows.filter { it.packageName != launcherPackage && it.packageName in limitedApps }
        val beside = limited.firstOrNull { !it.isPictureInPicture }?.packageName
        val pip = limited.firstOrNull { it.isPictureInPicture && isLocked(it.packageName) }?.packageName
        return HomeDecision(beside ?: launcherPackage, pip)
    }

    /**
     * The launcher a picture-in-picture window's event really means. Tapping or moving a PiP
     * window sends window events from its app, but the app isn't in front: what is in front is the
     * top full window. [windows] are top first. Null when that isn't a launcher.
     */
    fun launcherUnderPip(windows: List<ScreenWindow>, launchers: Set<String>): String? =
        windows.firstOrNull { !it.isPictureInPicture }?.packageName?.takeIf { it in launchers }
}
