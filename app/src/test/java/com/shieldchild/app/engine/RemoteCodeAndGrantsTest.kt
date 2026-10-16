package com.shieldchild.app.engine

import android.content.ContextWrapper
import com.shieldchild.app.data.GrantMethod
import com.shieldchild.app.data.LockReason
import com.shieldchild.app.data.PreferencesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RemoteCodeAndGrantsTest {

    private val store = FakePrefs()
    private var uptime = 1_000_000L
    private val repo: PreferencesRepository = testRepo(store) { uptime }
    private val auth = ParentAuthManager(repo)
    private val plainCipher = object : SecretCipher {
        override fun encrypt(plain: ByteArray) = "enc:" + plain.joinToString(",")
        override fun decrypt(stored: String) = stored.removePrefix("enc:").split(",").map { it.toByte() }.toByteArray()
    }
    private val remote = RemoteCodeManager(repo, auth, plainCipher)
    private val context = ContextWrapper(null)
    private val now = 1_700_000_000_000L

    private fun setUp(): ByteArray {
        val secret = remote.newSecret()
        assertTrue(remote.confirmSetup(secret, Totp.code(secret, Totp.step(now)), now))
        return secret
    }

    @Test
    fun setupNeedsAMatchingCodeAndConfirmationCodeCantBeReused() {
        val secret = remote.newSecret()
        assertFalse(remote.confirmSetup(secret, "000000", now))
        assertFalse(remote.isSetUp())
        assertTrue(remote.confirmSetup(secret, Totp.code(secret, Totp.step(now)), now))
        assertTrue(remote.redeem(Totp.code(secret, Totp.step(now)), now) is RedeemResult.Invalid)
    }

    @Test
    fun codeGives15MinutesByDefaultAndWorksOnce() {
        val secret = setUp()
        val later = now + 60_000
        val code = Totp.code(secret, Totp.step(later))
        assertEquals(RedeemResult.Success(15), remote.redeem(code, later))
        assertTrue(remote.redeem(code, later) is RedeemResult.Invalid)
    }

    @Test
    fun maxThreeUnlocksPerDay() {
        val secret = setUp()
        repeat(3) { i ->
            val t = now + (i + 1) * 60_000L
            assertTrue(remote.redeem(Totp.code(secret, Totp.step(t)), t) is RedeemResult.Success)
        }
        val t = now + 5 * 60_000L
        assertEquals(RedeemResult.DailyCapReached, remote.redeem(Totp.code(secret, Totp.step(t)), t))
    }

    @Test
    fun wrongCodesShareThePinLockout() {
        setUp()
        auth.setMasterPin("4829")
        repeat(4) { remote.redeem("000000", now + 60_000) }
        assertTrue(remote.redeem("000000", now + 60_000) is RedeemResult.LockedOut)
        assertTrue(auth.verifyPin("4829") is AuthResult.LockedOut)
    }

    @Test
    fun secretIsOnlyStoredEncrypted() {
        val secret = setUp()
        assertFalse(store.values.values.any { it == Totp.base32(secret) })
        assertTrue(repo.getRemoteSecretEncrypted()!!.startsWith("enc:"))
    }

    @Test
    fun grantDuringLimitPausesOnlyTheLimit() {
        AccessGrants.grantMinutes(context, repo, 15, LockReason.TOTAL_SCREEN_TIME_EXCEEDED, GrantMethod.PIN, now = System.currentTimeMillis())
        val access = repo.getAccessState()
        assertTrue(access.isLimitPaused)
        assertFalse(access.isBedtimePaused)
        assertEquals("MINUTES:15", repo.getGrantLog().first().description)
    }

    @Test
    fun grantDuringBedtimePausesBedtimeAndLimit() {
        AccessGrants.grantMinutes(context, repo, 15, LockReason.SCHEDULED_BEDTIME_CURFEW, GrantMethod.REMOTE_CODE)
        val access = repo.getAccessState()
        assertTrue(access.isBedtimePaused && access.isLimitPaused)
        assertEquals(GrantMethod.REMOTE_CODE, repo.getGrantLog().first().method)
    }

    @Test
    fun restOfBedtimeEndsAtCurfewEnd() {
        val tenPm = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 14, 22, 0) }
        val end = AccessGrants.nextCurfewEnd(repo, tenPm)
        val expected = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 15, 7, 0) }
        assertEquals(expected.timeInMillis, end)
    }

    @Test
    fun parentSessionEndsAfterFiveIdleMinutes() {
        AccessGrants.startParentSession(context, repo, GrantMethod.PIN)
        assertTrue(repo.getAccessState().isParentSession)
        uptime += 4 * 60_000
        assertTrue(repo.isParentSessionActive())
        uptime += 2 * 60_000
        assertFalse(repo.isParentSessionActive())
    }

    @Test
    fun parentSessionEndsAfterTenMinutesEvenWithTouches() {
        AccessGrants.startParentSession(context, repo, GrantMethod.PIN)
        repeat(9) {
            uptime += 60_000
            repo.touchParentSession()
        }
        assertTrue(repo.isParentSessionActive())
        uptime += 60_000
        repo.touchParentSession()
        assertFalse(repo.isParentSessionActive())
    }

    @Test
    fun parentTimeIsExcludedFromChildUsage() {
        AccessGrants.startParentSession(context, repo, GrantMethod.PIN)
        uptime += 3 * 60_000
        repo.endParentSession()
        assertEquals(3L, repo.getParentUsedMinutesToday())
        assertFalse(repo.hasParentSessionRecord())
    }
}
