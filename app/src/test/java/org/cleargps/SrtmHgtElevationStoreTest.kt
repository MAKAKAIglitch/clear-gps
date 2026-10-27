package org.cleargps

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SrtmHgtElevationStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun derivesTileNamesFromSouthWestCornerAndHandlesWorldEdges() {
        assertEquals("N52E039", srtmHgtTileStem(52.25, 39.75))
        assertEquals("S01W005", srtmHgtTileStem(-0.25, -4.2))
        assertEquals("N89E179", srtmHgtTileStem(90.0, 180.0))
        assertNull(srtmHgtTileStem(Double.NaN, 0.0))
        assertNull(srtmHgtTileStem(0.0, Double.POSITIVE_INFINITY))
        assertNull(srtmHgtTileStem(90.0001, 0.0))
        assertNull(srtmHgtTileStem(0.0, -180.0001))
    }

    @Test
    fun acceptsOnlySupportedSquareSrtmGridDimensions() {
        assertEquals(1201, srtmHgtSampleCount(1201L * 1201L * 2L))
        assertEquals(3601, srtmHgtSampleCount(3601L * 3601L * 2L))
        assertNull(srtmHgtSampleCount(0L))
        assertNull(srtmHgtSampleCount(7L))
        assertNull(srtmHgtSampleCount(5L * 5L * 2L))
        assertNull(srtmHgtSampleCount(1201L * 1201L * 2L - 2L))
    }

    @Test
    fun bilinearInterpolationUsesNeighboringSamples() {
        assertEquals(
            250.0,
            SrtmHgtInterpolation.bilinearMeters(
                northWest = 100,
                northEast = 200,
                southWest = 300,
                southEast = 400,
                fractionX = 0.5,
                fractionY = 0.5
            )!!,
            0.000001
        )
    }

    @Test
    fun zeroWeightVoidDoesNotInvalidateExactGridSample() {
        assertEquals(
            123.0,
            SrtmHgtInterpolation.bilinearMeters(
                northWest = 123,
                northEast = null,
                southWest = null,
                southEast = null,
                fractionX = 0.0,
                fractionY = 0.0
            )!!,
            0.0
        )
    }

    @Test
    fun aRequiredVoidOrInvalidFractionProducesNoElevation() {
        assertNull(
            SrtmHgtInterpolation.bilinearMeters(
                northWest = 100,
                northEast = -32768,
                southWest = 300,
                southEast = 400,
                fractionX = 0.5,
                fractionY = 0.5
            )
        )
        assertNull(
            SrtmHgtInterpolation.bilinearMeters(
                northWest = 100,
                northEast = 200,
                southWest = 300,
                southEast = 400,
                fractionX = Double.NaN,
                fractionY = 0.5
            )
        )
        assertNull(
            SrtmHgtInterpolation.bilinearMeters(
                northWest = null,
                northEast = null,
                southWest = null,
                southEast = null,
                fractionX = 0.5,
                fractionY = 0.5
            )
        )
    }

    @Test
    fun readsBigEndianTileAndInterpolatesAroundTheRequestedCoordinate() {
        val directory = temporaryFolder.newFolder("dem")
        val tile = File(directory, "N52E039.hgt")
        val size = 1201
        val row = 600
        val column = 600
        RandomAccessFile(tile, "rw").use { file ->
            file.setLength(size.toLong() * size * 2L)
            writeSample(file, size, row, column, 100)
            writeSample(file, size, row, column + 1, 200)
            writeSample(file, size, row + 1, column, 300)
            writeSample(file, size, row + 1, column + 1, 400)
        }

        val latitude = 53.0 - (row + 0.5) / (size - 1)
        val longitude = 39.0 + (column + 0.5) / (size - 1)
        val sample = SrtmHgtElevationStore(directory).elevationAt(latitude, longitude)

        assertEquals(250.0, sample!!.elevationMeters, 0.000001)
        assertEquals(SrtmVerticalDatum.EGM96_GEOID, sample.verticalDatum)
    }

    @Test
    fun missingOrMalformedTileReturnsNoEstimate() {
        val directory = temporaryFolder.newFolder("empty-dem")
        assertNull(SrtmHgtElevationStore(directory).elevationAt(52.5, 39.5))

        File(directory, "N52E039.hgt").writeBytes(byteArrayOf(0, 1, 2, 3))
        assertNull(SrtmHgtElevationStore(directory).elevationAt(52.5, 39.5))
    }

    @Test
    fun nodataCornerDoesNotBecomeZeroHeight() {
        val directory = temporaryFolder.newFolder("void-dem")
        val tile = File(directory, "N52E039.hgt")
        val size = 1201
        val row = 600
        val column = 600
        RandomAccessFile(tile, "rw").use { file ->
            file.setLength(size.toLong() * size * 2L)
            writeSample(file, size, row, column, -32768)
            writeSample(file, size, row, column + 1, 200)
            writeSample(file, size, row + 1, column, 300)
            writeSample(file, size, row + 1, column + 1, 400)
        }

        val latitude = 53.0 - (row + 0.5) / (size - 1)
        val longitude = 39.0 + (column + 0.5) / (size - 1)
        assertNull(SrtmHgtElevationStore(directory).elevationAt(latitude, longitude))
    }

    @Test
    fun nonDirectoryOrMissingTileIsNotTreatedAsZero() {
        val missingDirectory = File(temporaryFolder.root, "not-created")
        assertFalse(missingDirectory.exists())
        assertNull(SrtmHgtElevationStore(missingDirectory).elevationAt(52.5, 39.5))
    }

    private fun writeSample(file: RandomAccessFile, size: Int, row: Int, column: Int, value: Int) {
        file.seek((row.toLong() * size + column) * 2L)
        file.writeShort(value)
    }
}
