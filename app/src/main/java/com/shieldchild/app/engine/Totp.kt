package com.shieldchild.app.engine

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 time-based one-time codes, the same ones Google Authenticator shows.
 * Pure Kotlin so it can be checked against the RFC test vectors.
 */
object Totp {
    const val PERIOD_SECONDS = 30L

    fun step(timeMillis: Long): Long = timeMillis / 1000L / PERIOD_SECONDS

    fun code(secret: ByteArray, step: Long, digits: Int = 6): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        var modulus = 1
        repeat(digits) { modulus *= 10 }
        return (binary % modulus).toString().padStart(digits, '0')
    }

    /**
     * Returns the matching step if [code] is valid now (allowing one step either side for
     * slow typing or a slightly-off clock) and newer than [lastUsedStep]; null otherwise.
     */
    fun verify(secret: ByteArray, code: String, timeMillis: Long, lastUsedStep: Long): Long? {
        if (code.length != 6 || !code.all { it.isDigit() }) return null
        val now = step(timeMillis)
        return (now - 1..now + 1).firstOrNull { s ->
            s > lastUsedStep && java.security.MessageDigest.isEqual(code(secret, s).toByteArray(), code.toByteArray())
        }
    }

    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(BASE32[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(BASE32[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}
