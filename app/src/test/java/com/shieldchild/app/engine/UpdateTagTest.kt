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
}
