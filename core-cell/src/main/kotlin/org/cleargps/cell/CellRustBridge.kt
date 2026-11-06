package org.cleargps.cell

/** Optional JNI acceleration for the pure numerical kernels. Kotlin remains the safe fallback. */
internal object CellRustBridge {
    private val nativeLoaded: Boolean = runCatching {
        System.loadLibrary("clear_cell_intelligence")
    }.isSuccess

    val isAvailable: Boolean
        get() = nativeLoaded

    fun fitTimingAdvance(
        x: DoubleArray,
        y: DoubleArray,
        ranges: DoubleArray,
        sigmas: DoubleArray,
        maximumIterations: Int,
        bearingBinDegrees: Int
    ): DoubleArray? {
        if (!nativeLoaded) return null
        return runCatching {
            nativeFitTimingAdvance(
                x, y, ranges, sigmas, maximumIterations, bearingBinDegrees
            )
        }.getOrNull()
    }

    fun evaluateSector(
        bearings: DoubleArray,
        distances: DoubleArray,
        rssi: DoubleArray,
        angularBinDegrees: Int,
        minimumOccupiedBins: Int,
        minimumSignalContrastDb: Double,
        maximumModelSpreadDegrees: Double
    ): DoubleArray? {
        if (!nativeLoaded) return null
        return runCatching {
            nativeEvaluateSector(
                bearings,
                distances,
                rssi,
                angularBinDegrees,
                minimumOccupiedBins,
                minimumSignalContrastDb,
                maximumModelSpreadDegrees
            )
        }.getOrNull()
    }

    private external fun nativeFitTimingAdvance(
        x: DoubleArray,
        y: DoubleArray,
        ranges: DoubleArray,
        sigmas: DoubleArray,
        maximumIterations: Int,
        bearingBinDegrees: Int
    ): DoubleArray

    private external fun nativeEvaluateSector(
        bearings: DoubleArray,
        distances: DoubleArray,
        rssi: DoubleArray,
        angularBinDegrees: Int,
        minimumOccupiedBins: Int,
        minimumSignalContrastDb: Double,
        maximumModelSpreadDegrees: Double
    ): DoubleArray
}
