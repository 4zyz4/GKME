package com.zyz4.gkme.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the version parsing / comparison used by the update check. */
class UpdateCheckerVersionTest {

    @Test
    fun versionNumbers_acceptsReleaseTagsAndNightlyNames() {
        assertEquals(listOf(4, 10, 0), versionNumbers("4.10.0"))
        assertEquals(listOf(4, 11, 0), versionNumbers("v4.11.0"))
        assertEquals(listOf(4, 10, 0), versionNumbers("4.10.0-nightly.20261128"))
        assertEquals(listOf(5), versionNumbers("5"))
    }

    @Test
    fun versionNumbers_rejectsNonNumericNames() {
        assertNull(versionNumbers("nightly"))
        assertNull(versionNumbers(""))
        assertNull(versionNumbers("v"))
    }

    @Test
    fun compareVersion_ordersNumericSegmentsAndPadsMissingOnes() {
        assertTrue(compareVersion(listOf(4, 11, 0), listOf(4, 10, 0)) > 0)
        assertTrue(compareVersion(listOf(4, 9, 9), listOf(4, 10, 0)) < 0)
        assertTrue(compareVersion(listOf(5), listOf(4, 99, 99)) > 0)
        assertEquals(0, compareVersion(listOf(4, 10), listOf(4, 10, 0)))
    }
}
