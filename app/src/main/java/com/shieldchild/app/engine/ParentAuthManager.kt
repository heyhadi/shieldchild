package com.shieldchild.app.engine

import com.shieldchild.app.data.PreferencesRepository
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed class AuthResult {
    object Success : AuthResult()
    data class InvalidPin(val remainingAttempts: Int) : AuthResult()
    data class LockedOut(val remainingSeconds: Long) : AuthResult()
}

class ParentAuthManager(private val repository: PreferencesRepository) {

    fun setMasterPin(pin: String) {
        require(pin.length in 4..8 && pin.all { it.isDigit() }) { "PIN must be 4 to 8 digits" }
        val salt = generateSalt()
        repository.savePin(hashSecret(pin, salt), salt)
        resetFailures()
    }

    fun verifyPin(enteredPin: String): AuthResult {
        lockoutRemaining()?.let { return it }

        val storedHash = repository.getPinHash()
        val salt = repository.getPinSalt()
        // No PIN configured means nobody can authenticate; setup must create one.
        if (storedHash == null || salt == null) return AuthResult.InvalidPin(MAX_FAILED_ATTEMPTS)

        return if (matches(enteredPin, storedHash, salt)) {
            if (!storedHash.startsWith(PBKDF2_PREFIX)) setMasterPin(enteredPin) // upgrade legacy SHA-256 hash
            resetFailures()
            AuthResult.Success
        } else {
            recordFailure()
        }
    }

    fun hasRecoveryKey(): Boolean = repository.getRecoveryKeyHash() != null

    /**
     * Creates a new random recovery key, stores only its hash and returns the raw key
     * so it can be shown to the parent exactly once.
     */
    fun generateRecoveryKey(): String {
        val random = SecureRandom()
        val raw = (1..RECOVERY_KEY_LENGTH)
            .map { RECOVERY_ALPHABET[random.nextInt(RECOVERY_ALPHABET.length)] }
            .joinToString("")
        val salt = generateSalt()
        repository.saveRecoveryKeyHash(hashSecret(raw, salt), salt)
        return raw.chunked(4).joinToString("-")
    }

    /**
     * Emergency offline recovery: resets the PIN when the parent's recovery key matches.
     * Wrong keys count towards the same lockout as wrong PINs.
     */
    fun recoverWithMasterKey(recoveryKey: String, newPin: String): Boolean {
        if (newPin.length !in 4..8 || !newPin.all { it.isDigit() }) return false
        if (lockoutRemaining() != null) return false

        val storedHash = repository.getRecoveryKeyHash() ?: return false
        val salt = repository.getRecoveryKeySalt() ?: return false
        val sanitizedKey = recoveryKey.replace("-", "").replace(" ", "").trim().uppercase()

        if (!matches(sanitizedKey, storedHash, salt)) {
            recordFailure()
            return false
        }
        setMasterPin(newPin)
        return true
    }

    /** Shared by PIN, recovery key and remote codes so guesses across all of them count together. */
    fun lockoutRemaining(): AuthResult.LockedOut? {
        val lockoutUntil = repository.getLockoutUntilTimestamp()
        val now = System.currentTimeMillis()
        return if (lockoutUntil > now) {
            AuthResult.LockedOut(TimeUnit.MILLISECONDS.toSeconds(lockoutUntil - now))
        } else {
            null
        }
    }

    fun recordFailure(): AuthResult {
        val failed = repository.getFailedAttempts() + 1
        repository.setFailedAttempts(failed)
        return if (failed >= MAX_FAILED_ATTEMPTS) {
            repository.setLockoutUntilTimestamp(System.currentTimeMillis() + LOCKOUT_DURATION_MS)
            AuthResult.LockedOut(TimeUnit.MILLISECONDS.toSeconds(LOCKOUT_DURATION_MS))
        } else {
            AuthResult.InvalidPin(MAX_FAILED_ATTEMPTS - failed)
        }
    }

    fun resetFailures() {
        repository.setFailedAttempts(0)
        repository.setLockoutUntilTimestamp(0)
    }

    private fun matches(secret: String, storedHash: String, salt: String): Boolean {
        if (secret.isEmpty()) return false // PBKDF2 throws "password empty" and crashed the PIN gate
        val computed = if (storedHash.startsWith(PBKDF2_PREFIX)) hashSecret(secret, salt) else legacySha256(secret, salt)
        return MessageDigest.isEqual(storedHash.toByteArray(), computed.toByteArray())
    }

    private fun hashSecret(secret: String, salt: String): String {
        val spec = PBEKeySpec(secret.toCharArray(), salt.toByteArray(), PBKDF2_ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return PBKDF2_PREFIX + bytes.toHex()
    }

    private fun legacySha256(secret: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt.toByteArray())
        return md.digest(secret.toByteArray()).toHex()
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_FAILED_ATTEMPTS = 5
        val LOCKOUT_DURATION_MS = TimeUnit.MINUTES.toMillis(15)

        private const val PBKDF2_PREFIX = "pbkdf2$"
        private const val PBKDF2_ITERATIONS = 120_000
        private const val RECOVERY_KEY_LENGTH = 16
        // No 0/O or 1/I, so a handwritten key can't be misread.
        private const val RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
