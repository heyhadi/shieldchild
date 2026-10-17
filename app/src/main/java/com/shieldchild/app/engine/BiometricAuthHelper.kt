package com.shieldchild.app.engine

import com.shieldchild.app.i18n.str
import com.shieldchild.app.R
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.shieldchild.app.ShieldApplication
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

sealed class BiometricStatus {
    object Available : BiometricStatus()
    object NotEnrolled : BiometricStatus()
    object HardwareUnavailable : BiometricStatus()
    object Unsupported : BiometricStatus()
}

/**
 * Parent fingerprint unlock. Every prompt is bound to a Keystore key that Android
 * invalidates when a new fingerprint is enrolled, so a fingerprint the child adds
 * later can never pass as the parent's.
 */
class BiometricAuthHelper(private val activity: FragmentActivity) {

    private val biometricManager = BiometricManager.from(activity)
    private val repo get() = ShieldApplication.instance.preferencesRepository

    fun checkBiometricAvailability(): BiometricStatus {
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.Available
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NotEnrolled
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.HardwareUnavailable
            else -> BiometricStatus.Unsupported
        }
    }

    /** The phone has a fingerprint sensor with at least one fingerprint enrolled. */
    fun isHardwareReady(): Boolean = checkBiometricAvailability() is BiometricStatus.Available

    /** The parent turned fingerprint unlock on and the bound key is still present. */
    fun isBiometricReady(): Boolean {
        val um = activity.getSystemService(android.content.Context.USER_SERVICE) as? android.os.UserManager
        if (um?.isUserUnlocked == false) return false
        return try {
            isHardwareReady() && repo.isBiometricEnabled() && loadKey() != null
        } catch (_: Throwable) {
            false
        }
    }

    /** Creates a fresh key and asks for a fingerprint; turns fingerprint unlock on when it succeeds. */
    fun enable(onSuccess: () -> Unit, onError: (CharSequence) -> Unit) {
        val cipher = try {
            createKey()
            initCipher()
        } catch (e: Exception) {
            onError(str(R.string.fingerprint_unlock_isn_t_supported))
            return
        }
        authenticate(
            cipher,
            title = str(R.string.turn_on_parent_fingerprint),
            subtitle = str(R.string.scan_the_parent_s_fingerprint),
            negativeButtonText = str(R.string.cancel),
            onSuccess = {
                repo.setBiometricEnabled(true)
                onSuccess()
            },
            onError = { _, err -> deleteKey(); onError(err) },
            onFailed = {}
        )
    }

    fun disable() {
        repo.setBiometricEnabled(false)
        deleteKey()
    }

    fun promptBiometricAuth(
        title: String = str(R.string.parent_fingerprint_verification),
        subtitle: String = str(R.string.scan_your_fingerprint_to_unlock),
        negativeButtonText: String = str(R.string.use_master_pin),
        onSuccess: () -> Unit,
        onError: (errorCode: Int, errString: CharSequence) -> Unit,
        onFailed: () -> Unit
    ) {
        if (!isBiometricReady()) {
            onError(BiometricPrompt.ERROR_NO_BIOMETRICS, str(R.string.fingerprint_unlock_is_off_use))
            return
        }
        val cipher = try {
            initCipher()
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable()
            onError(
                BiometricPrompt.ERROR_NO_BIOMETRICS,
                str(R.string.a_new_fingerprint_was_added)
            )
            return
        } catch (e: Exception) {
            onError(BiometricPrompt.ERROR_VENDOR, str(R.string.fingerprint_unavailable_use_your_pin))
            return
        }
        authenticate(cipher, title, subtitle, negativeButtonText, onSuccess, onError, onFailed)
    }

    private fun authenticate(
        cipher: Cipher,
        title: String,
        subtitle: String,
        negativeButtonText: String,
        onSuccess: () -> Unit,
        onError: (Int, CharSequence) -> Unit,
        onFailed: () -> Unit
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // Using the cipher proves the Keystore accepted this fingerprint.
                val ok = try {
                    result.cryptoObject?.cipher?.doFinal(CHALLENGE) != null
                } catch (_: Exception) {
                    false
                }
                if (ok) onSuccess() else onError(BiometricPrompt.ERROR_VENDOR, str(R.string.fingerprint_check_failed_use_your))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onError(errorCode, errString)
            override fun onAuthenticationFailed() = onFailed()
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButtonText)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            .authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    private fun initCipher(): Cipher {
        val key = loadKey() ?: throw IllegalStateException(str(R.string.no_fingerprint_key))
        return Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    private fun createKey() {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(spec)
            generateKey()
        }
    }

    private fun loadKey(): SecretKey? = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey
    } catch (_: Exception) {
        null
    }

    private fun deleteKey() {
        try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {}
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "shieldchild_parent_biometric"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val CHALLENGE = "shieldchild".toByteArray()
    }
}
