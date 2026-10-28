package org.cleargps

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class SrtmHgtTileInventory(val tileCount: Int, val totalBytes: Long)

internal data class SrtmHgtTileImportResult(
    val importedTiles: Int,
    val importedBytes: Long,
    val error: String? = null
) {
    val succeeded: Boolean get() = error == null && importedTiles > 0
}

/** Private, offline storage for user-supplied SRTM HGT archives. */
internal class SrtmHgtTilePackStore(internal val directory: File) {
    fun inventory(): SrtmHgtTileInventory {
        val validFiles = directory.listFiles().orEmpty().filter { file ->
            file.isFile && canonicalSrtmHgtTileName(file.name) != null &&
                srtmHgtSampleCount(file.length()) != null
        }
        return SrtmHgtTileInventory(validFiles.size, validFiles.sumOf { it.length() })
    }

    /** Consumes and closes the ZIP input stream; archive paths are never used as output paths. */
    fun importZip(input: InputStream): SrtmHgtTileImportResult {
        if (!directory.exists() && !directory.mkdirs()) {
            runCatching { input.close() }
            return failure("Не удалось создать приватную папку DEM.")
        }
        if (!directory.isDirectory) {
            runCatching { input.close() }
            return failure("Путь хранения DEM не является папкой.")
        }

        val staging = File(directory, ".dem-import-${System.nanoTime()}")
        if (!staging.mkdir()) {
            runCatching { input.close() }
            return failure("Не удалось создать временную папку импорта.")
        }
        val staged = linkedMapOf<String, File>()
        var totalUncompressedBytes = 0L
        var committedTiles = 0
        var committedBytes = 0L
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entryCount = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ARCHIVE_ENTRIES) {
                        throw InvalidDemArchive("В архиве слишком много элементов.")
                    }
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    val basename = entry.name.replace('\\', '/').substringAfterLast('/')
                    val isHgt = basename.substringAfterLast('.', "").equals("hgt", ignoreCase = true)
                    val canonicalName = if (isHgt) canonicalSrtmHgtTileName(basename)
                        ?: throw InvalidDemArchive("В архиве есть HGT-файл с недопустимым именем.") else null

                    if (canonicalName != null) {
                        if (staged.containsKey(canonicalName)) {
                            throw InvalidDemArchive("Архив содержит повторяющийся тайл $canonicalName.")
                        }
                        if (staged.size >= MAX_TILES_PER_ARCHIVE) {
                            throw InvalidDemArchive("В одном архиве допускается не больше $MAX_TILES_PER_ARCHIVE HGT-тайлов.")
                        }
                    }

                    val stagingFile = canonicalName?.let { File(staging, it) }
                    var entryBytes = 0L
                    val output = stagingFile?.outputStream()
                    try {
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            entryBytes += read.toLong()
                            totalUncompressedBytes += read.toLong()
                            if (totalUncompressedBytes > MAX_UNCOMPRESSED_ARCHIVE_BYTES) {
                                throw InvalidDemArchive("Распакованный архив превышает допустимый лимит 512 MiB.")
                            }
                            if (stagingFile != null && entryBytes > MAX_HGT_TILE_BYTES) {
                                throw InvalidDemArchive("HGT-тайл превышает допустимый размер.")
                            }
                            output?.write(buffer, 0, read)
                        }
                    } finally {
                        output?.close()
                    }
                    zip.closeEntry()

                    if (canonicalName != null && stagingFile != null) {
                        if (srtmHgtSampleCount(stagingFile.length()) == null) {
                            throw InvalidDemArchive("HGT-тайл $canonicalName имеет неподдерживаемый размер.")
                        }
                        staged[canonicalName] = stagingFile
                    }
                }
            }

            if (staged.isEmpty()) {
                throw InvalidDemArchive("В ZIP не найдено корректных SRTM HGT-тайлов.")
            }
            for ((name, stagedFile) in staged) {
                val destination = File(directory, name)
                try {
                    Files.move(stagedFile.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(stagedFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                committedTiles++
                committedBytes += destination.length()
            }
            return SrtmHgtTileImportResult(committedTiles, committedBytes)
        } catch (error: InvalidDemArchive) {
            return SrtmHgtTileImportResult(committedTiles, committedBytes, error.message ?: "Архив DEM не прошёл проверку.")
        } catch (_: IOException) {
            return SrtmHgtTileImportResult(committedTiles, committedBytes, "Архив не читается или не удалось безопасно сохранить тайлы.")
        } catch (_: SecurityException) {
            return SrtmHgtTileImportResult(committedTiles, committedBytes, "Android запретил доступ к архиву или папке DEM.")
        } finally {
            runCatching { staging.deleteRecursively() }
        }
    }

    private fun failure(message: String) = SrtmHgtTileImportResult(0, 0, message)
    private class InvalidDemArchive(message: String) : IOException(message)

    private companion object {
        const val COPY_BUFFER_BYTES = 16 * 1024
        const val MAX_ARCHIVE_ENTRIES = 1_000
        const val MAX_TILES_PER_ARCHIVE = 50
        const val MAX_HGT_TILE_BYTES = 25_934_402L
        const val MAX_UNCOMPRESSED_ARCHIVE_BYTES = 512L * 1024L * 1024L
    }
}

/** Validates an HGT filename and returns a canonical uppercase tile name, or null. */
internal fun canonicalSrtmHgtTileName(fileName: String): String? {
    val basename = fileName.substringAfterLast('/').substringAfterLast('\\')
    if (!basename.endsWith(".hgt", ignoreCase = true)) return null
    val stem = basename.dropLast(4).uppercase(Locale.ROOT)
    val match = HGT_STEM_PATTERN.matchEntire(stem) ?: return null
    val latHemisphere = match.groupValues[1]
    val latMagnitude = match.groupValues[2].toIntOrNull() ?: return null
    val lonHemisphere = match.groupValues[3]
    val lonMagnitude = match.groupValues[4].toIntOrNull() ?: return null
    val validLatitude = when (latHemisphere) {
        "N" -> latMagnitude in 0..89
        "S" -> latMagnitude in 1..90
        else -> false
    }
    val validLongitude = when (lonHemisphere) {
        "E" -> lonMagnitude in 0..179
        "W" -> lonMagnitude in 1..180
        else -> false
    }
    return if (validLatitude && validLongitude) "$stem.hgt" else null
}

private val HGT_STEM_PATTERN = Regex("^([NS])(\\d{2})([EW])(\\d{3})$")
