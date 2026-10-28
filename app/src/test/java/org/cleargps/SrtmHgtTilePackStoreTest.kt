package org.cleargps

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SrtmHgtTilePackStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val validTileBytes = ByteArray(1201 * 1201 * 2)

    @Test
    fun importsValidatedTileFromNestedPathAndNormalizesName() {
        val directory = temporaryFolder.newFolder("srtm")
        val store = SrtmHgtTilePackStore(directory)
        val result = store.importZip(archive(
            "region/subfolder/n52e039.HGT" to validTileBytes,
            "README.txt" to "local DEM".toByteArray()
        ))
        assertTrue(result.succeeded)
        assertEquals(1, result.importedTiles)
        assertEquals(1, store.inventory().tileCount)
        assertTrue(File(directory, "N52E039.hgt").isFile)
        assertEquals(0.0, SrtmHgtElevationStore(directory).elevationAt(52.5, 39.5)!!.elevationMeters, 0.0)
    }

    @Test
    fun malformedHgtArchiveDoesNotCommitPreviouslyStagedValidTile() {
        val directory = temporaryFolder.newFolder("atomic-invalid")
        val store = SrtmHgtTilePackStore(directory)
        val result = store.importZip(archive(
            "N52E039.hgt" to validTileBytes,
            "N52E040.hgt" to byteArrayOf(0, 1, 2, 3)
        ))
        assertFalse(result.succeeded)
        assertEquals(0, result.importedTiles)
        assertEquals(0, store.inventory().tileCount)
    }

    @Test
    fun rejectsInvalidLatitudeAndLongitudeTileNames() {
        assertNull(canonicalSrtmHgtTileName("N90E039.hgt"))
        assertNull(canonicalSrtmHgtTileName("N52E180.hgt"))
        assertNull(canonicalSrtmHgtTileName("S00W001.hgt"))
        assertNull(canonicalSrtmHgtTileName("N52W000.hgt"))
        assertEquals("S90W180.hgt", canonicalSrtmHgtTileName("s90w180.HGT"))
    }

    @Test
    fun duplicateTileNamesAreRejectedWithoutPartialImport() {
        val store = SrtmHgtTilePackStore(temporaryFolder.newFolder("duplicates"))
        val result = store.importZip(archive(
            "N52E039.hgt" to validTileBytes,
            "nested/N52E039.HGT" to validTileBytes
        ))
        assertFalse(result.succeeded)
        assertEquals(0, store.inventory().tileCount)
    }

    @Test
    fun archiveWithoutHgtTileDoesNotChangeInventory() {
        val store = SrtmHgtTilePackStore(temporaryFolder.newFolder("empty-archive"))
        val result = store.importZip(archive("readme.txt" to byteArrayOf(1, 2, 3)))
        assertFalse(result.succeeded)
        assertEquals(0, store.inventory().tileCount)
    }

    @Test
    fun inventoryIgnoresInvalidFilesAndCountsOnlyValidTiles() {
        val directory = temporaryFolder.newFolder("inventory")
        File(directory, "N52E039.hgt").writeBytes(validTileBytes)
        File(directory, "N90E039.hgt").writeBytes(validTileBytes)
        File(directory, "N52E040.hgt").writeBytes(byteArrayOf(0, 1, 2))
        File(directory, "notes.txt").writeText("no tile")
        val inventory = SrtmHgtTilePackStore(directory).inventory()
        assertEquals(1, inventory.tileCount)
        assertEquals(validTileBytes.size.toLong(), inventory.totalBytes)
    }

    @Test
    fun rejectsNonZipInput() {
        val store = SrtmHgtTilePackStore(temporaryFolder.newFolder("not-zip"))
        val result = store.importZip(ByteArrayInputStream("not a zip".toByteArray()))
        assertFalse(result.succeeded)
        assertEquals(0, store.inventory().tileCount)
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
