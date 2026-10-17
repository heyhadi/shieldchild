package com.shieldchild.app.engine

import com.shieldchild.app.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateTagTest {
    @Test
    fun readsBuildNumberAfterTheDash() {
        assertEquals(2L, UpdateManager.buildFromTag("v1.0.1-2"))
        assertEquals(17L, UpdateManager.buildFromTag("1.2.0-17"))
    }

    @Test
    fun olderOrOddTagsNeverCountAsNewer() {
        assertEquals(0L, UpdateManager.buildFromTag("v1.0.0")) // the release before the updater existed
        assertEquals(0L, UpdateManager.buildFromTag("latest"))
        assertEquals(0L, UpdateManager.buildFromTag("v1.0.1-beta"))
    }

    @Test
    fun persistsAndRetrievesCachedUpdate() {
        val repo = testRepo()
        org.junit.Assert.assertNull(repo.getCachedUpdate())

        val update = com.shieldchild.app.update.AvailableUpdate(
            title = "ShieldChild v1.0.30",
            apkUrl = "https://github.com/heyhadi/shieldchild/releases/download/v1.0.30-30/shieldchild.apk",
            build = 30L
        )
        repo.setCachedUpdate(update)

        val cached = repo.getCachedUpdate()
        org.junit.Assert.assertNotNull(cached)
        assertEquals(30L, cached!!.build)
        assertEquals("ShieldChild v1.0.30", cached.title)
        assertEquals("https://github.com/heyhadi/shieldchild/releases/download/v1.0.30-30/shieldchild.apk", cached.apkUrl)

        // Clear cached update
        repo.setCachedUpdate(null)
        org.junit.Assert.assertNull(repo.getCachedUpdate())
    }

    @Test
    fun tracksDismissedUpdateBuild() {
        val repo = testRepo()
        org.junit.Assert.assertFalse(repo.isUpdateBannerDismissedForBuild(30L))

        repo.dismissUpdateBannerForBuild(30L)
        org.junit.Assert.assertTrue(repo.isUpdateBannerDismissedForBuild(30L))
        org.junit.Assert.assertFalse(repo.isUpdateBannerDismissedForBuild(31L))
    }
}

