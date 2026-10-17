package com.shieldchild.app.engine

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneGuardTest {

    @After
    fun tearDown() {
        PhoneGuard.resetForTest()
    }

    @Test
    fun strictInCallPackagesAlwaysYield() {
        val context = TestContext()
        assertTrue(PhoneGuard.isPhonePackage(context, "com.android.phone"))
        assertTrue(PhoneGuard.isPhonePackage(context, "com.android.server.telecom"))
        assertTrue(PhoneGuard.isPhonePackage(context, "com.android.incallui"))
        assertTrue(PhoneGuard.isPhonePackage(context, "com.samsung.android.incallui"))
        assertTrue(PhoneGuard.shouldYield(context, "com.android.phone"))
    }

    @Test
    fun fullDialerPackagesDoNotYieldOutsideCommunicationWindowOrCall() {
        val context = TestContext()
        PhoneGuard.resetForTest()
        assertFalse(PhoneGuard.isPhonePackage(context, "com.google.android.dialer"))
        assertFalse(PhoneGuard.isPhonePackage(context, "com.android.dialer"))
        assertFalse(PhoneGuard.isPhonePackage(context, "com.samsung.android.dialer"))
        assertFalse(PhoneGuard.shouldYield(context, "com.google.android.dialer"))
    }

    @Test
    fun fullDialerPackagesYieldDuringCommunicationWindow() {
        val context = TestContext()
        PhoneGuard.allowExternalCommunicationWindow(30_000L)
        assertTrue(PhoneGuard.isWithinCommunicationWindow())
        assertTrue(PhoneGuard.isPhonePackage(context, "com.google.android.dialer"))
        assertTrue(PhoneGuard.isPhonePackage(context, "com.android.dialer"))
        assertTrue(PhoneGuard.isPhonePackage(context, "com.samsung.android.dialer"))
        assertTrue(PhoneGuard.shouldYield(context, "com.google.android.dialer"))

        // After window reset/expiry, dialer no longer yields
        PhoneGuard.resetForTest()
        assertFalse(PhoneGuard.isWithinCommunicationWindow())
        assertFalse(PhoneGuard.isPhonePackage(context, "com.google.android.dialer"))
        assertFalse(PhoneGuard.shouldYield(context, "com.google.android.dialer"))
    }

    @Test
    fun nonPhonePackagesNeverYieldEvenDuringCommunicationWindow() {
        val context = TestContext()
        PhoneGuard.allowExternalCommunicationWindow(30_000L)
        assertFalse(PhoneGuard.isPhonePackage(context, "com.discord"))
        assertFalse(PhoneGuard.isPhonePackage(context, "com.roblox"))
        assertFalse(PhoneGuard.isPhonePackage(context, "com.google.android.youtube"))
        assertFalse(PhoneGuard.shouldYield(context, "com.discord"))
        assertFalse(PhoneGuard.shouldYield(context, "com.roblox"))
    }

    @Test
    fun whitelistedSessionYieldsOnlyForTargetPackageAndCanBeRevoked() {
        val context = TestContext()
        PhoneGuard.startWhitelistedSession("com.whatsapp")
        assertTrue(PhoneGuard.isWhitelistedSessionActiveFor("com.whatsapp"))
        assertTrue(PhoneGuard.shouldYield(context, "com.whatsapp"))
        assertFalse(PhoneGuard.shouldYield(context, "com.google.android.youtube"))

        PhoneGuard.clearWhitelistedSession()
        assertFalse(PhoneGuard.isWhitelistedSessionActiveFor("com.whatsapp"))
        assertFalse(PhoneGuard.shouldYield(context, "com.whatsapp"))
    }

    @Test
    fun isAllowedVoipCallRecognizesWhatsAppAndRejectsOtherApps() {
        val audioManager = android.media.TestAudioManager(android.media.AudioManager.MODE_IN_COMMUNICATION)
        val context = object : android.content.ContextWrapper(null) {
            override fun getSystemService(name: String): Any? =
                if (name == android.content.Context.AUDIO_SERVICE) audioManager else null
        }

        assertTrue(PhoneGuard.isAllowedVoipCall(context, "com.whatsapp"))
        assertTrue(PhoneGuard.isAllowedVoipCall(context, "com.whatsapp.w4b"))
        assertFalse(PhoneGuard.isAllowedVoipCall(context, "com.discord"))
    }

    @Test
    fun whitelistedSessionAllowsKeyboardsAndSystemHelpers() {
        val context = TestContext()
        PhoneGuard.startWhitelistedSession("com.whatsapp")
        // Keyboards and system helpers yield while session is active
        assertTrue(PhoneGuard.shouldYield(context, "com.google.android.inputmethod.latin"))
        assertTrue(PhoneGuard.shouldYield(context, "com.android.systemui"))
        // Unrelated apps do NOT yield
        assertFalse(PhoneGuard.shouldYield(context, "com.google.android.youtube"))

        // When session ends, keyboards do NOT yield
        PhoneGuard.clearWhitelistedSession()
        assertFalse(PhoneGuard.shouldYield(context, "com.google.android.inputmethod.latin"))
    }
}
