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
}
