package com.shieldchild.app.engine

import com.shieldchild.app.service.ShieldAccessibilityService
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsGuardTest {
    @After
    fun reset() = ShieldAccessibilityService.revokeParentUninstall()

    @Test
    fun staysOffBeforeSetupSoTheParentCanGrantPermissions() {
        assertFalse(ShieldAccessibilityService.shouldGuardSettings(isSetupCompleted = false))
    }

    @Test
    fun guardsSettingsOnceSetupIsDone() {
        assertTrue(ShieldAccessibilityService.shouldGuardSettings(isSetupCompleted = true))
    }

    @Test
    fun parentOpenedSettingsAreAllowedForTheWindow() {
        ShieldAccessibilityService.allowParentUninstall()
        assertFalse(ShieldAccessibilityService.shouldGuardSettings(isSetupCompleted = true))
    }
}
