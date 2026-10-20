package com.shieldchild.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitScaleTest {
    private val app = LimitScale.APP
    private val daily = LimitScale.DAILY_TOTAL

    @Test
    fun sliderStopsLandOnEveryStep() {
        assertEquals(34, app.sliderSteps) // 5, 10, … 180 → 36 stops, 34 between the ends
        assertEquals(22, daily.sliderSteps) // 15, 30, … 360 → 24 stops
        assertEquals(5f..180f, app.sliderRange)
        assertEquals(15f..360f, daily.sliderRange)
    }

    @Test
    fun sliderValuesSnapToWholeSteps() {
        assertEquals(20L, app.fromSlider(19.9999f))
        assertEquals(20L, app.fromSlider(21.4f))
        assertEquals(5L, app.fromSlider(5f))
        assertEquals(180L, app.fromSlider(180f))
        assertEquals(45L, daily.fromSlider(44.7f))
        assertEquals(360L, daily.fromSlider(400f))
        assertEquals(15L, daily.fromSlider(0f))
    }

    @Test
    fun buttonsReachAnyMinuteFromOneUpToTheMax() {
        assertEquals(19L, app.nudge(20L, -1L))
        assertEquals(21L, app.nudge(20L, +1L))
        assertEquals(1L, app.nudge(2L, -1L))
        assertEquals(1L, app.nudge(1L, -1L)) // never 0, which would mean "no limit"
        assertEquals(180L, app.nudge(180L, +1L))
        assertEquals(359L, daily.nudge(360L, -1L))
        assertEquals(2L, daily.nudge(1L, +1L))
    }

    @Test
    fun buttonsDisableAtTheEnds() {
        assertFalse(app.canDecrease(1L))
        assertTrue(app.canDecrease(2L))
        assertFalse(app.canIncrease(180L))
        assertTrue(app.canIncrease(179L))
        assertFalse(daily.canIncrease(360L))
    }

    @Test
    fun offStepAndOutOfRangeSavedValuesStillShow() {
        // An old or button-set value off the slider's steps keeps the thumb at its exact spot.
        assertEquals(47f, app.sliderPosition(47L))
        assertEquals(100f, daily.sliderPosition(100L))
        // Below the slider's start (set with −) the thumb pins to the start.
        assertEquals(5f, app.sliderPosition(1L))
        assertEquals(15f, daily.sliderPosition(3L))
        // Above the max (e.g. an older build's value) the thumb pins to the end, and + can't grow it.
        assertEquals(180f, app.sliderPosition(240L))
        assertFalse(app.canIncrease(240L))
        assertEquals(180L, app.nudge(240L, -1L))
    }
}
