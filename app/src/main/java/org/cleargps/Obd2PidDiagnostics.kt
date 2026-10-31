package org.cleargps

/**
 * Trace of one allowlisted Mode 01 PID from response decoding to its real in-app consumers.
 * Sample ages for PID 0C/0D use monotonic request midpoints; other PIDs only have the
 * telemetry-cycle wall-clock timestamp and are explicitly labelled as approximate.
 */
internal data class Obd2PidTrace(
    val pid: Int,
    val label: String,
    val value: String?,
    val issue: Obd2PidIssue?,
    val ageMillis: Long?,
    val ageBasis: String,
    val consumers: List<String>
)

internal object Obd2PidDiagnostics {
    private const val NS_PER_MS = 1_000_000L

    fun rows(
        telemetry: Obd2Telemetry,
        nowEpochMillis: Long,
        nowElapsedRealtimeNanos: Long
    ): List<Obd2PidTrace> = listOf(
        row(
            telemetry, 0x0D,
            telemetry.vehicleSpeedKmh?.let { "$it км/ч" },
            telemetry.pidIssues[0x0D] ?: when (telemetry.speedIssue) {
                Obd2SpeedIssue.PID_UNSUPPORTED -> Obd2PidIssue.PID_UNSUPPORTED
                Obd2SpeedIssue.NO_VALID_RESPONSE -> Obd2PidIssue.NO_VALID_RESPONSE
                Obd2SpeedIssue.PID_DISABLED_FOR_SESSION -> Obd2PidIssue.PID_DISABLED_FOR_SESSION
                Obd2SpeedIssue.TRANSIENT_ZERO_SUPPRESSED -> Obd2PidIssue.TRANSIENT_ZERO_SUPPRESSED
                null -> null
            },
            telemetry.speedSampleElapsedRealtimeNanos,
            nowEpochMillis, nowElapsedRealtimeNanos,
            "монотонная середина PID-запроса",
            listOf(
                "LocationMonitorService · признаки скорости для антиспуфинга",
                "Obd2LocationFusion · короткий GPS-прогноз (до 30 с / 300 м)",
                "Obd2SpeedCalibration · сверка с физическим GNSS",
                "BatteryProfilePolicy · адаптивный интервал радио-сканирования",
                "Obd2DriveMetricsTracker · путь/ускорение на экране OBD",
                "Obd2GearRatioLearner · пара RPM/скорость вместе с PID 0C"
            )
        ),
        row(
            telemetry, 0x0C,
            telemetry.engineRpm?.let { "$it об/мин" },
            telemetry.pidIssues[0x0C],
            telemetry.engineRpmSampleElapsedRealtimeNanos,
            nowEpochMillis, nowElapsedRealtimeNanos,
            "монотонная середина PID-запроса",
            listOf("Obd2GearRatioLearner · временно согласованная пара RPM/скорость")
        ),
        row(
            telemetry, 0x04,
            telemetry.engineLoadPercent?.let { "$it %" },
            telemetry.pidIssues[0x04],
            null, nowEpochMillis, nowElapsedRealtimeNanos,
            "время завершения цикла опроса (приблизительно)",
            listOf("Obd2GearRatioLearner · исключение высокой нагрузки из обучения")
        ),
        row(
            telemetry, 0x11,
            telemetry.throttlePercent?.let { "$it %" },
            telemetry.pidIssues[0x11],
            null, nowEpochMillis, nowElapsedRealtimeNanos,
            "время завершения цикла опроса (приблизительно)",
            listOf("Obd2GearRatioLearner · исключение сильного нажатия газа из обучения")
        ),
        row(
            telemetry, 0x0B,
            telemetry.manifoldPressureKpa?.let { "$it кПа" },
            telemetry.pidIssues[0x0B],
            null, nowEpochMillis, nowElapsedRealtimeNanos,
            "время завершения цикла опроса (приблизительно)",
            listOf("Внутреннего потребителя пока нет · экран / внешний broadcast по согласию")
        ),
        row(
            telemetry, 0x05,
            telemetry.coolantTemperatureCelsius?.let { "$it °C" },
            telemetry.pidIssues[0x05],
            null, nowEpochMillis, nowElapsedRealtimeNanos,
            "время завершения цикла опроса (приблизительно)",
            listOf("Внутреннего потребителя пока нет · экран / внешний broadcast по согласию")
        ),
        row(
            telemetry, 0x33,
            telemetry.barometricPressureKpa?.let { "$it кПа" },
            telemetry.pidIssues[0x33],
            null, nowEpochMillis, nowElapsedRealtimeNanos,
            "время завершения цикла опроса (приблизительно)",
            listOf("Внутреннего потребителя пока нет · экран / внешний broadcast по согласию; P3.6 ещё не подключён")
        )
    )

    private fun row(
        telemetry: Obd2Telemetry,
        pid: Int,
        value: String?,
        issue: Obd2PidIssue?,
        sampleElapsedRealtimeNanos: Long?,
        nowEpochMillis: Long,
        nowElapsedRealtimeNanos: Long,
        ageBasis: String,
        consumers: List<String>
    ): Obd2PidTrace {
        val ageMillis = if (sampleElapsedRealtimeNanos != null) {
            if (sampleElapsedRealtimeNanos > 0L &&
                nowElapsedRealtimeNanos >= sampleElapsedRealtimeNanos
            ) (nowElapsedRealtimeNanos - sampleElapsedRealtimeNanos) / NS_PER_MS else null
        } else {
            if (telemetry.updatedAt > 0L && nowEpochMillis >= telemetry.updatedAt) {
                nowEpochMillis - telemetry.updatedAt
            } else null
        }
        return Obd2PidTrace(
            pid = pid,
            label = Obd2Protocol.pidLabel(pid),
            value = value,
            issue = issue,
            ageMillis = ageMillis,
            ageBasis = ageBasis,
            consumers = consumers
        )
    }
}
