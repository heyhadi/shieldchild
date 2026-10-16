package com.shieldchild.app.engine

import kotlin.math.abs

/**
 * Detects a child moving the clock to skip bedtime or reset the daily limit. Uptime
 * (elapsedRealtime) can't be changed by the user, so a wall-clock jump that uptime doesn't
 * explain is a manual change. Automatic network time only nudges the clock, and while it's on
 * the user can't set the time at all, so only manual-time phones are checked.
 */
object ClockGuard {
    const val MAX_UNEXPLAINED_JUMP_MS = 2 * 60_000L

    fun isSuspiciousJump(
        lastWallMs: Long,
        lastUptimeMs: Long,
        nowWallMs: Long,
        nowUptimeMs: Long,
        isAutomaticTime: Boolean
    ): Boolean {
        if (isAutomaticTime || lastWallMs == 0L) return false
        val expectedWall = lastWallMs + (nowUptimeMs - lastUptimeMs)
        return abs(nowWallMs - expectedWall) > MAX_UNEXPLAINED_JUMP_MS
    }
}
