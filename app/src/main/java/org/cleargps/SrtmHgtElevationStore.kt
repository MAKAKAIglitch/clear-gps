package org.cleargps

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Small, offline-only reader for SRTM HGT tiles.
 *
 * The caller chooses the local tile directory. This class never downloads data, searches the
 * network, or invents a height for a missing tile / void. HGT elevations use the EGM96 geoid
 * reference, so callers must not silently treat this value as ellipsoid height.
 */
internal class SrtmHgtElevationStore(private val tileDirectory: File) {
    fun elevationAt(latitude: Double, longitude: Double): SrtmHgtElevationSample? {
        val tileStem = srtmHgtTileStem(latitude, longitude) ?: return null
        if (!tileDirectory.isDirectory) return null
        val file = resolveTile(tileStem) ?: return null

        return try {
            RandomAccessFile(file, "r").use { raster ->
                val sampleCount = srtmHgtSampleCount(raster.length()) ?: return null
                val span = (sampleCount - 1).toDouble()
                val tileSouth = if (latitude == 90.0) 89 else floor(latitude).toInt()
                val tileWest = if (longitude == 180.0) 179 else floor(longitude).toInt()

                // HGT stores the northernmost row first and proceeds southward.
                val row = ((tileSouth + 1.0 - latitude) * span).coerceIn(0.0, span)
                val column = ((longitude - tileWest) * span).coerceIn(0.0, span)
                val row0 = floor(row).toInt().coerceIn(0, sampleCount - 1)
                val row1 = (row0 + 1).coerceAtMost(sampleCount - 1)
                val column0 = floor(column).toInt().coerceIn(0, sampleCount - 1)
                val column1 = (column0 + 1).coerceAtMost(sampleCount - 1)

                val fractionY = (row - row0).coerceIn(0.0, 1.0)
                val fractionX = (column - column0).coerceIn(0.0, 1.0)
                val elevation = SrtmHgtInterpolation.bilinearMeters(
                    northWest = readElevation(raster, sampleCount, row0, column0),
                    northEast = readElevation(raster, sampleCount, row0, column1),
                    southWest = readElevation(raster, sampleCount, row1, column0),
                    southEast = readElevation(raster, sampleCount, row1, column1),
                    fractionX = fractionX,
                    fractionY = fractionY
                ) ?: return null

                SrtmHgtElevationSample(elevationMeters = elevation)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun resolveTile(stem: String): File? {
        val names = listOf(
            "$stem.hgt", "$stem.HGT",
            stem.lowercase() + ".hgt", stem.lowercase() + ".HGT"
        )
        return names.asSequence()
            .map { File(tileDirectory, it) }
            .firstOrNull { it.isFile }
    }

    private fun readElevation(
        raster: RandomAccessFile,
        sampleCount: Int,
        row: Int,
        column: Int
    ): Int? {
        val offset = (row.toLong() * sampleCount + column) * BYTES_PER_SAMPLE
        raster.seek(offset)
        val value = raster.readShort().toInt() // RandomAccessFile reads signed big-endian shorts.
        return value.takeUnless { it == NO_DATA }
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2L
        const val NO_DATA = -32768
    }
}

internal data class SrtmHgtElevationSample(
    val elevationMeters: Double,
    val verticalDatum: SrtmVerticalDatum = SrtmVerticalDatum.EGM96_GEOID
)

internal enum class SrtmVerticalDatum {
    EGM96_GEOID
}

/** Returns the canonical one-degree HGT tile stem for a valid coordinate. */
internal fun srtmHgtTileStem(latitude: Double, longitude: Double): String? {
    if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
    if (!longitude.isFinite() || longitude !in -180.0..180.0) return null

    // The poles and +180° meridian are represented by the adjacent edge sample, not N90/E180.
    val southLatitude = if (latitude == 90.0) 89 else floor(latitude).toInt()
    val westLongitude = if (longitude == 180.0) 179 else floor(longitude).toInt()
    val latHemisphere = if (southLatitude >= 0) 'N' else 'S'
    val lonHemisphere = if (westLongitude >= 0) 'E' else 'W'
    return "$latHemisphere${kotlin.math.abs(southLatitude).toString().padStart(2, '0')}" +
        "$lonHemisphere${kotlin.math.abs(westLongitude).toString().padStart(3, '0')}"
}

/** Dimensions of supported one- and three-arc-second SRTM HGT tiles. */
internal fun srtmHgtSampleCount(fileLengthBytes: Long): Int? {
    if (fileLengthBytes <= 0L || fileLengthBytes % 2L != 0L) return null
    val sampleCount = sqrt((fileLengthBytes / 2L).toDouble()).toInt()
    if (sampleCount !in setOf(1201, 3601)) return null
    return sampleCount.takeIf { it.toLong() * it * 2L == fileLengthBytes }
}

/** Bilinear interpolation; a void only invalidates the result when its weight is non-zero. */
internal object SrtmHgtInterpolation {
    fun bilinearMeters(
        northWest: Int?,
        northEast: Int?,
        southWest: Int?,
        southEast: Int?,
        fractionX: Double,
        fractionY: Double
    ): Double? {
        if (!fractionX.isFinite() || fractionX !in 0.0..1.0) return null
        if (!fractionY.isFinite() || fractionY !in 0.0..1.0) return null

        val weightedSamples = listOf(
            (1.0 - fractionX) * (1.0 - fractionY) to northWest,
            fractionX * (1.0 - fractionY) to northEast,
            (1.0 - fractionX) * fractionY to southWest,
            fractionX * fractionY to southEast
        )
        var result = 0.0
        for ((weight, elevation) in weightedSamples) {
            if (weight <= 0.0) continue
            val value = elevation ?: return null
            if (value == -32768) return null
            result += weight * value
        }
        return result.takeIf { it.isFinite() }
    }
}
