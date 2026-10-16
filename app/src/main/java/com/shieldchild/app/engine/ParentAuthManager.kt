package com.shieldchild.app.engine

import com.shieldchild.app.data.PreferencesRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

sealed class AuthResult {
    object Success : AuthResult()
    data class InvalidPin(val remainingAttempts: Int) : AuthResult()
    data class LockedOut(val remainingSeconds: Long) : AuthResult()
}

class ParentAuthManager(private val repository: PreferencesRepository) {

    fun setMasterPin(pin: String) {
        require(pin.length in 4..8) { "PIN must be between 4 and 8 digits" }
        val salt = generateSalt()
        val hash = hashPin(pin, salt)
        repository.savePin(hash, salt)
        repository.setFailedAttempts(0)
        repository.setLockoutUntilTimestamp(0)
    }

    fun verifyPin(enteredPin: String): AuthResult {
        val now = System.currentTimeMillis()
        val lockoutUntil = repository.getLockoutUntilTimestamp()

        if (lockoutUntil > now) {
            val remainingSec = TimeUnit.MILLISECONDS.toSeconds(lockoutUntil - now)
            return AuthResult.LockedOut(remainingSec)
        }

        val storedHash = repository.getPinHash()
        val salt = repository.getPinSalt()

        // Fallback for first launch if no PIN set yet: default is "1234"
        if (storedHash == null || salt == null) {
            if (enteredPin == "1234") {
                setMasterPin("1234")
                return AuthResult.Success
            } else {
                return AuthResult.InvalidPin(MAX_FAILED_ATTEMPTS - 1)
            }
        }

        val enteredHash = hashPin(enteredPin, salt)
        if (MessageDigest.isEqual(storedHash.toByteArray(), enteredHash.toByteArray())) {
            repository.setFailedAttempts(0)
            repository.setLockoutUntilTimestamp(0)
            return AuthResult.Success
        } else {
            val failed = repository.getFailedAttempts() + 1
            repository.setFailedAttempts(failed)

            if (failed >= MAX_FAILED_ATTEMPTS) {
                val lockoutExpiry = now + LOCKOUT_DURATION_MS
                repository.setLockoutUntilTimestamp(lockoutExpiry)
                return AuthResult.LockedOut(TimeUnit.MILLISECONDS.toSeconds(LOCKOUT_DURATION_MS))
            } else {
                return AuthResult.InvalidPin(MAX_FAILED_ATTEMPTS - failed)
            }
        }
    }

    fun getOrInitMasterRecoveryKey(): String {
        val key = repository.getMasterRecoveryKey()
        if (repository.getRecoveryKeyHash() == null) {
            setMasterRecoveryKey(key)
        }
        return key
    }

    /**
     * Emergency offline recovery: Parent enters their 16-character Master Recovery Key.
     * Bypasses PIN lockout and resets PIN to a new value.
     */
    fun recoverWithMasterKey(recoveryKey: String, newPin: String): Boolean {
        if (newPin.length < 4 || newPin.length > 8 || !newPin.all { it.isDigit() }) {
            return false
        }
        val sanitizedKey = recoveryKey.replace("-", "").trim().uppercase()
        val expectedKey = getOrInitMasterRecoveryKey().replace("-", "").trim().uppercase()

        if (sanitizedKey == expectedKey) {
            setMasterPin(newPin)
            repository.setFailedAttempts(0)
            repository.setLockoutUntilTimestamp(0)
            return true
        }
        return false
    }

    fun setMasterRecoveryKey(rawKey: String) {
        val sanitized = rawKey.replace("-", "").trim().uppercase()
        val salt = repository.getPinSalt() ?: generateSalt()
        val hash = hashPin(sanitized, salt)
        repository.saveRecoveryKeyHash(hash)
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt.toByteArray())
        val bytes = md.digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateSalt(): String {
        val random = SecureRandom()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_FAILED_ATTEMPTS = 5
        val LOCKOUT_DURATION_MS = TimeUnit.MINUTES.toMillis(15)
    }
}
