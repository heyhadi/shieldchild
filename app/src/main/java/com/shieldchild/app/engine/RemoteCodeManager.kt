package com.shieldchild.app.engine

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.shieldchild.app.data.PreferencesRepository
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets at rest. The default keeps its key in the Android Keystore. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): String
    fun decrypt(stored: String): ByteArray
}

class KeystoreSecretCipher : SecretCipher {
    override fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val body = Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)
        return "$iv:$body"
    }

    override fun decrypt(stored: String): ByteArray {
        val (iv, body) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        return cipher.doFinal(Base64.decode(body, Base64.NO_WRAP))
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    private companion object {
        const val ALIAS = "shieldchild_remote_code"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

sealed class RedeemResult {
    data class Success(val minutes: Long) : RedeemResult()
    data class Invalid(val remainingAttempts: Int) : RedeemResult()
    data class LockedOut(val remainingSeconds: Long) : RedeemResult()
    object DailyCapReached : RedeemResult()
    object NotSetUp : RedeemResult()
}

/**
 * Remote unlock: the parent reads a 6-digit code from Google Authenticator and the child
 * types it on the lock screen. Fully offline; the shared secret never leaves this phone
 * except through the QR code shown once at setup.
 */
class RemoteCodeManager(
    private val repo: PreferencesRepository,
    private val auth: ParentAuthManager,
    private val cipher: SecretCipher = KeystoreSecretCipher()
) {
    fun isSetUp(): Boolean = try {
        repo.getRemoteSecretEncrypted() != null
    } catch (_: Throwable) {
        false
    }

    /** New secret for setup. Nothing is saved until [confirmSetup] succeeds. */
    fun newSecret(): ByteArray = ByteArray(20).also { SecureRandom().nextBytes(it) }

    fun otpauthUri(secret: ByteArray, childName: String): String {
        val label = "ShieldChild:" + childName.ifBlank { "Child" }.replace(Regex("[^A-Za-z0-9 ]"), "").replace(" ", "%20")
        return "otpauth://totp/$label?secret=${Totp.base32(secret)}&issuer=ShieldChild&algorithm=SHA1&digits=6&period=30"
    }

    /** Saves [secret] only if [code] proves the parent's authenticator scanned it. */
    fun confirmSetup(secret: ByteArray, code: String, now: Long = System.currentTimeMillis()): Boolean {
        val step = Totp.verify(secret, code.trim(), now, lastUsedStep = 0L) ?: return false
        repo.saveRemoteSecretEncrypted(cipher.encrypt(secret))
        repo.setRemoteLastStep(step) // the confirmation code can't be reused by the child
        return true
    }

    fun turnOff() = repo.saveRemoteSecretEncrypted(null)

    fun redeem(code: String, now: Long = System.currentTimeMillis()): RedeemResult {
        val stored = repo.getRemoteSecretEncrypted() ?: return RedeemResult.NotSetUp
        auth.lockoutRemaining()?.let { return RedeemResult.LockedOut(it.remainingSeconds) }
        if (repo.getRemoteUnlocksToday() >= MAX_UNLOCKS_PER_DAY) return RedeemResult.DailyCapReached

        val secret = try {
            cipher.decrypt(stored)
        } catch (_: Throwable) {
            return RedeemResult.NotSetUp
        }
        val step = Totp.verify(secret, code.trim(), now, repo.getRemoteLastStep())
        if (step == null) {
            return when (val r = auth.recordFailure()) {
                is AuthResult.LockedOut -> RedeemResult.LockedOut(r.remainingSeconds)
                is AuthResult.InvalidPin -> RedeemResult.Invalid(r.remainingAttempts)
                AuthResult.Success -> RedeemResult.Invalid(0)
            }
        }
        repo.setRemoteLastStep(step)
        repo.incrementRemoteUnlocksToday()
        auth.resetFailures()
        return RedeemResult.Success(repo.getRemoteCodeMinutes())
    }

    companion object {
        const val MAX_UNLOCKS_PER_DAY = 3
    }
}
