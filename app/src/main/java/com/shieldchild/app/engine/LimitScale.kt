package com.shieldchild.app.engine

import kotlin.math.roundToLong

/**
 * Step and clamp maths for the time-limit sliders in the parent dashboard.
 * The slider moves in coarse [step]s between [min] and [max]; the −/+ buttons
 * nudge by one minute anywhere from [FLOOR] up to [max], so any exact value is reachable.
 */
data class LimitScale(val min: Long, val max: Long, val step: Long) {
    val sliderRange: ClosedFloatingPointRange<Float> get() = min.toFloat()..max.toFloat()

    /** Compose's `steps` counts the stops between the two ends. */
    val sliderSteps: Int get() = ((max - min) / step - 1).toInt()

    /** Where the thumb sits for a saved value; values off the slider's range pin to its ends. */
    fun sliderPosition(minutes: Long): Float = minutes.coerceIn(min, max).toFloat()

    /** Turns a raw slider value into whole minutes on the nearest step. */
    fun fromSlider(value: Float): Long {
        val snapped = min + ((value - min) / step).roundToLong() * step
        return snapped.coerceIn(min, max)
    }

    /** One −/+ press: changes by [delta] minutes, never below [FLOOR] or above [max]. */
    fun nudge(minutes: Long, delta: Long): Long = (minutes + delta).coerceIn(FLOOR, max)

    fun canDecrease(minutes: Long): Boolean = minutes > FLOOR
    fun canIncrease(minutes: Long): Boolean = minutes < max

    companion object {
        /** 0 means "no limit" to PolicyEngine, so the smallest real limit is 1 minute. */
        const val FLOOR = 1L

        val APP = LimitScale(min = 5L, max = 180L, step = 5L)
        val DAILY_TOTAL = LimitScale(min = 15L, max = 360L, step = 15L)
    }
}
