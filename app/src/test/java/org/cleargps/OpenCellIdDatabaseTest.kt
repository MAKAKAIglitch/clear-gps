package org.cleargps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenCellIdDatabaseTest {
    @Test fun towerCatalogueIsPersistedSeparatelyFromLocalRadioPoints() {
        val context = RuntimeEnvironment.getApplication()
        val name = "opencellid-test-${System.nanoTime()}.sqlite"
        val database = LocalRadioDatabase(context, name)
        try {
            val key = OpenCellIdCellKey("LTE", 234, 15, 100, 55221L)
            val record = OpenCellIdCellRecord(key, 51.5, -0.12, 800, 7, false, 10_000L, "found")
            assertEquals(true, database.saveOpenCellIdCell(record))
            val loaded = database.getOpenCellIdCell(key)
            assertNotNull(loaded)
            assertEquals(record, loaded)
            assertEquals(1, database.listOpenCellIdCells().size)
            assertEquals(0, database.pointCount())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun negativeLookupIsCachedButNotDisplayedAsAFoundTower() {
        val context = RuntimeEnvironment.getApplication()
        val name = "opencellid-negative-test-${System.nanoTime()}.sqlite"
        val database = LocalRadioDatabase(context, name)
        try {
            val key = OpenCellIdCellKey("GSM", 234, 15, 1, 2L)
            val record = OpenCellIdCellRecord(key, null, null, null, 0, false, 10_000L, "not_found")
            assertEquals(true, database.saveOpenCellIdCell(record))
            assertEquals("not_found", database.getOpenCellIdCell(key)?.status)
            assertEquals(0, database.listOpenCellIdCells().size)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
