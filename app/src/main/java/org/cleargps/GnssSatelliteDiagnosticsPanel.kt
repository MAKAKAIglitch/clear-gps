package org.cleargps

import android.location.GnssStatus
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
internal fun GnssSatelliteDiagnosticsPanel(
    snapshot: GnssDiagnosticsLiveSnapshot?,
    monitorEnabled: Boolean,
    radioOnly: Boolean
) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Спутники GNSS", style = MaterialTheme.typography.titleSmall)
            when {
                radioOnly -> Text(
                    "В режиме «Только радио» приложение не запрашивает GNSS, поэтому спутниковые данные не собираются.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                )
                !monitorEnabled -> Text(
                    "Включи мониторинг в режиме GPS или Авто, чтобы получать данные от GNSS-приёмника.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                )
                snapshot == null -> Text(
                    "Нет свежего снимка от GNSS-приёмника. Ожидание данных; предыдущие спутники не показываются как текущие.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                )
                else -> {
                    val satellites = snapshot.satellites
                    Text(
                        "Видно: ${satellites.size} · используется в фиксе: ${snapshot.usedInFixCount}",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                    )
                    val meanCn0 = snapshot.meanCn0DbHz
                    Text(
                        if (meanCn0 != null && meanCn0.isFinite()) "Средний C/N₀: " + String.format(Locale.US, "%.1f dB-Hz", meanCn0)
                        else "C/N₀ не предоставлен приёмником",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                    )
                    if (satellites.isNotEmpty()) {
                        GnssSkyPlot(satellites)
                        Text(
                            "Сверху — север. Кольца соответствуют высоте 0°, 30°, 60°; центр — 90° над горизонтом.",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(Modifier.size(8.dp), shape = CircleShape, color = colors.primary) {}
                                Text("В фиксе", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(Modifier.size(8.dp), shape = CircleShape, color = colors.tertiary) {}
                                Text("Видим", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                        }
                        Text("Уровень сигнала по спутникам", style = MaterialTheme.typography.labelLarge)
                        val ordered = satellites.sortedWith(
                            compareByDescending<GnssSatelliteReading> { it.usedInFix }
                                .thenByDescending { it.cn0DbHz ?: -1.0 }
                                .thenBy { it.constellationType }.thenBy { it.svid }
                        )
                        ordered.take(48).forEach { SatelliteReadingRow(it) }
                        if (ordered.size > 48) Text(
                            "Показаны 48 из ${ordered.size} спутников.",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
                        )
                    } else Text(
                        "Приёмник сообщил, что видимых спутников сейчас нет. Причина по этому факту неизвестна.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                    )
                    val satelliteAgcCount = satellites.count { it.agcDb != null }
                    when {
                        snapshot.agcBands.isNotEmpty() -> {
                            Text("AGC по диапазону (API 33+)", style = MaterialTheme.typography.labelLarge)
                            snapshot.agcBands.forEach { band ->
                                Text(
                                    "${constellationName(band.constellationType)} · ${formatFrequency(band.carrierFrequencyHz)} · AGC " +
                                        String.format(Locale.US, "%.1f dB", band.levelDb),
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                                )
                            }
                        }
                        satelliteAgcCount > 0 -> Text(
                            "AGC доступен для ${satelliteAgcCount} спутниковых измерений.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                        )
                        else -> Text(
                            "AGC в текущем снимке недоступен. Это ограничение устройства/API, а не признак помех.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                        )
                    }
                    Text(
                        "Данные живые, хранятся только в памяти и автоматически исчезают после потери свежести. Значения AGC зависят от аппаратуры и сами по себе не подтверждают спуфинг или глушение.",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun SatelliteReadingRow(satellite: GnssSatelliteReading) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(Modifier.size(9.dp), shape = CircleShape, color = if (satellite.usedInFix) colors.primary else colors.tertiary) {}
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "${constellationName(satellite.constellationType)} · SVID ${satellite.svid}",
                style = MaterialTheme.typography.bodySmall
            )
            val cn0 = satellite.cn0DbHz?.let { String.format(Locale.US, "%.1f dB-Hz", it) } ?: "C/N₀ —"
            val elevation = satellite.elevationDegrees?.takeIf { it >= 0.0 }?.let { "${it.roundToInt()}°" } ?: "высота —"
            val azimuth = satellite.azimuthDegrees?.let { "${it.roundToInt()}° аз." } ?: "азимут —"
            val frequency = (satellite.reportedCarrierFrequencyHz ?: satellite.measurementCarrierFrequencyHz)
                ?.let(::formatFrequency) ?: "частота —"
            Text(listOf(cn0, elevation, azimuth, frequency).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            satellite.basebandCn0DbHz?.let {
                Text("Baseband C/N₀: " + String.format(Locale.US, "%.1f dB-Hz", it), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            satellite.agcDb?.let {
                Text("AGC: " + String.format(Locale.US, "%.1f dB", it), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        Text(if (satellite.usedInFix) "В фиксе" else "Видим", style = MaterialTheme.typography.labelSmall, color = if (satellite.usedInFix) colors.primary else colors.onSurfaceVariant)
    }
}

@Composable
private fun GnssSkyPlot(satellites: List<GnssSatelliteReading>) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(230.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = (min(size.width, size.height) / 2f - 12.dp.toPx()).coerceAtLeast(0f)
        val stroke = Stroke(width = 1.dp.toPx())
        listOf(1f, 2f / 3f, 1f / 3f).forEach { fraction ->
            drawCircle(color = colors.outlineVariant, radius = radius * fraction, center = center, style = stroke)
        }
        drawLine(colors.outlineVariant, Offset(center.x - radius, center.y), Offset(center.x + radius, center.y), 1.dp.toPx())
        drawLine(colors.outlineVariant, Offset(center.x, center.y - radius), Offset(center.x, center.y + radius), 1.dp.toPx())
        satellites.forEach { satellite ->
            val azimuth = satellite.azimuthDegrees ?: return@forEach
            val elevation = satellite.elevationDegrees ?: return@forEach
            if (azimuth !in 0.0..360.0 || elevation !in 0.0..90.0) return@forEach
            val distance = radius * (1.0 - elevation / 90.0).toFloat()
            val angle = Math.toRadians(azimuth)
            drawCircle(
                color = if (satellite.usedInFix) colors.primary else colors.tertiary,
                radius = 5.dp.toPx(),
                center = Offset(center.x + distance * sin(angle).toFloat(), center.y - distance * cos(angle).toFloat())
            )
        }
    }
}

private fun constellationName(type: Int): String = when (type) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
    GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
    GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
    GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
    GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
    else -> "Неизвестно"
}

private fun formatFrequency(frequencyHz: Double): String =
    String.format(Locale.US, "%.2f MHz", frequencyHz / 1_000_000.0)
