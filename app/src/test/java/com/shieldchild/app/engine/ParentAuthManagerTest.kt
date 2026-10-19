package com.shieldchild.app.engine

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.shieldchild.app.data.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ParentAuthManagerTest {

    private val store = FakePrefs()
    private val repo = PreferencesRepository(object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = store
    })
    private val auth = ParentAuthManager(repo)

    @Test
    fun noDefaultPinWhenNoneIsSet() {
        assertTrue(auth.verifyPin("1234") !is AuthResult.Success)
        assertFalse(repo.hasPinConfigured())
    }

    @Test
    fun pinRoundTripAndWrongPin() {
        auth.setMasterPin("482915")
        assertEquals(AuthResult.Success, auth.verifyPin("482915"))
        assertEquals(AuthResult.InvalidPin(4), auth.verifyPin("000000"))
    }

    @Test
    fun emptyPinOrRecoveryKeyIsRejectedNotACrash() {
        auth.setMasterPin("4829")
        auth.generateRecoveryKey()
        assertTrue(auth.verifyPin("") is AuthResult.InvalidPin)
        assertFalse(auth.recoverWithMasterKey(" - ", "1234"))
    }

    @Test
    fun locksOutAfterFiveWrongPins() {
        auth.setMasterPin("4829")
        repeat(4) { auth.verifyPin("0000") }
        assertTrue(auth.verifyPin("0000") is AuthResult.LockedOut)
        assertTrue(auth.verifyPin("4829") is AuthResult.LockedOut)
    }

    @Test
    fun legacySha256PinStillWorksAndIsUpgraded() {
        val salt = "abc123"
        val md = MessageDigest.getInstance("SHA-256").apply { update(salt.toByteArray()) }
        val legacy = md.digest("4829".toByteArray()).joinToString("") { "%02x".format(it) }
        repo.savePin(legacy, salt)

        assertEquals(AuthResult.Success, auth.verifyPin("4829"))
        assertTrue(repo.getPinHash()!!.startsWith("pbkdf2$"))
        assertEquals(AuthResult.Success, auth.verifyPin("4829"))
    }

    @Test
    fun recoveryKeysAreRandomAndOnlyHashesAreStored() {
        auth.setMasterPin("4829")
        val first = auth.generateRecoveryKey()
        val second = auth.generateRecoveryKey()
        assertNotEquals(first, second)
        assertTrue(Regex("[A-Z2-9]{4}(-[A-Z2-9]{4}){3}").matches(second))
        assertFalse(store.values.values.any { it.toString().contains(second.replace("-", "")) })
    }

    @Test
    fun recoveryKeyResetsPinAndOldKeyStopsWorking() {
        auth.setMasterPin("4829")
        val old = auth.generateRecoveryKey()
        val current = auth.generateRecoveryKey()

        assertFalse(auth.recoverWithMasterKey(old, "1111"))
        assertTrue(auth.recoverWithMasterKey(current.lowercase(), "5555"))
        assertEquals(AuthResult.Success, auth.verifyPin("5555"))
    }

    @Test
    fun wrongRecoveryKeysHitTheLockout() {
        auth.setMasterPin("4829")
        val key = auth.generateRecoveryKey()
        repeat(5) { auth.recoverWithMasterKey("AAAA-AAAA-AAAA-AAAA", "1111") }
        assertFalse(auth.recoverWithMasterKey(key, "1111"))
    }

    @Test
    fun legacySharedRecoveryKeyIsRemovedOnLoad() {
        val legacy = FakePrefs().apply {
            edit().putString("key_recovery_key_raw", "SHLD-9A4F-K82X-7M1P").putString("key_recovery_key_hash", "x").apply()
        }
        val legacyRepo = PreferencesRepository(object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = legacy
        })
        val legacyAuth = ParentAuthManager(legacyRepo)
        assertFalse(legacyAuth.hasRecoveryKey())
        assertFalse(legacyAuth.recoverWithMasterKey("SHLD-9A4F-K82X-7M1P", "1111"))
    }
}
