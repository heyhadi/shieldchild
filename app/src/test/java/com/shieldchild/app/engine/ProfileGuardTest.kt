package com.shieldchild.app.engine

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileGuardTest {

    @After
    fun tearDown() {
        ProfileGuard.resetForTest(true)
    }

    @Test
    fun `background transition immediately marks profile inactive`() {
        val context = TestContext()
        ProfileGuard.onUserForeground()
        assertTrue(ProfileGuard.isCurrentProfileActive(context))

        ProfileGuard.onUserBackground()
        assertFalse(ProfileGuard.isCurrentProfileActive(context))
        assertFalse(ProfileGuard.isScreenInteractiveForProfile(context))
    }

    @Test
    fun `foreground transition restores profile active state`() {
        val context = TestContext()
        ProfileGuard.onUserBackground()
        assertFalse(ProfileGuard.isCurrentProfileActive(context))

        ProfileGuard.onUserForeground()
        assertTrue(ProfileGuard.isCurrentProfileActive(context))
    }
}
