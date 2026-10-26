package org.cleargps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun GnssReplayPanel(
    monitorEnabled: Boolean, radioOnly: Boolean, radioCaptureAvailable: Boolean,
    recording: Boolean, canStart: Boolean,
    hasSavedLog: Boolean, sizeBytes: Long, stopReason: String, replayResult: String,
    onStart: () -> Unit, onStop: () -> Unit, onExport: () -> Unit,
    onImport: () -> Unit, onDelete: () -> Unit
) {
    var showConsent by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Запись GNSS и реплей", style = MaterialTheme.typography.titleSmall)
            Text(
                "Запись выключена по умолчанию. После явного подтверждения сохраняются спутниковые GNSS-поля, GPS/network-трек в относительных координатах East/North и доступные показания акселерометра, гироскопа, магнитометра и ориентации (до 5 образцов/с на тип). Абсолютные координаты и календарное время не записываются.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
            Text(
                if (radioCaptureAvailable) {
                    "В режиме «Авто» во время записи дополнительно собираются доступные Wi‑Fi, сотовые и BLE-наблюдения. Сканирование выполняется не чаще раза в 15 секунд (или реже по интервалу в настройках); Android может ограничить его. В режиме GPS дополнительного радио-сканирования нет."
                } else {
                    "В текущем режиме дополнительные радиосканирования для replay не запускаются. Трек и IMU могут раскрывать характер движения и положение/поворот телефона. Файл хранится только в закрытом хранилище приложения; экспортируй его вручную. Лимит — 2 МиБ."
                },
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
            Text(
                "Можно открыть Clear GPS replay v1/v2 или CSV Google GnssLogger. В v2 записываются анонимные радионаблюдения с временным порядковым ID на одну сессию; BSSID, SSID, ID сот и BLE-адреса не экспортируются. CSV поддерживает Raw, Fix, Status, Agc и доступные Accel/Gyro/Mag/Orientation. IMU и радио пока не влияют на оценку риска.",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
            )
            val sizeKiB = "%.1f".format(java.util.Locale.ROOT, sizeBytes / 1024.0)
            Text(
                when {
                    recording -> "Запись активна · $sizeKiB КиБ из 2048 КиБ"
                    hasSavedLog -> "Локальная запись готова · $sizeKiB КиБ"
                    stopReason == "SIZE_LIMIT" -> "Запись остановлена при достижении лимита файла."
                    stopReason == "INTERRUPTED" -> "Запись прервана перезапуском приложения; доступен неполный файл."
                    stopReason == "SERVICE_STOP" -> "Запись остановлена вместе с мониторингом."
                    else -> "Запись не активна."
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (recording) {
                    FilledTonalButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Остановить") }
                } else {
                    FilledTonalButton(
                        onClick = { showConsent = true }, enabled = canStart && monitorEnabled && !radioOnly,
                        modifier = Modifier.weight(1f)
                    ) { Text("Начать запись") }
                }
                OutlinedButton(onClick = onExport, enabled = hasSavedLog && !recording, modifier = Modifier.weight(1f)) {
                    Text("Экспорт")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onImport, enabled = monitorEnabled && !radioOnly && !recording, modifier = Modifier.weight(1f)) {
                    Text("Открыть реплей")
                }
                TextButton(onClick = { showDeleteConfirm = true }, enabled = hasSavedLog && !recording) {
                    Text("Удалить файл")
                }
            }
            if (!monitorEnabled || radioOnly) Text(
                "Для записи включи мониторинг в режиме GPS или Авто; режим «Только радио» GNSS не считывает.",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
            )
            if (replayResult.isNotBlank()) Text(replayResult, style = MaterialTheme.typography.bodySmall, color = colors.primary)
        }
    }
    if (showConsent) AlertDialog(
        onDismissRequest = { showConsent = false },
        title = { Text("Разрешить локальную запись GNSS?") },
        text = { Text(
            "Будут сохранены доступные спутниковые поля, относительный GPS/network-трек, сводка фильтра и доступная IMU до 5 образцов/с на тип. " +
                (if (radioCaptureAvailable) "В режиме «Авто» при записи дополнительно собираются радионаблюдения с интервалом не чаще раза в 15 секунд. " else "В текущем режиме дополнительный сбор радио не запускается. ") +
                "Реальные BSSID, SSID, ID сотовых ячеек и BLE-адреса в файл не входят; вместо них используются временные порядковые ID только внутри записи. Трек, датчики и состав радио-наблюдений могут раскрывать контекст движения. Экспорт только вручную; IMU и радио пока не влияют на оценку риска. Автостоп — 2 МиБ или остановка мониторинга."
        ) },
        confirmButton = { TextButton(onClick = { showConsent = false; onStart() }) { Text("Начать запись") } },
        dismissButton = { TextButton(onClick = { showConsent = false }) { Text("Отмена") } }
    )
    if (showDeleteConfirm) AlertDialog(
        onDismissRequest = { showDeleteConfirm = false },
        title = { Text("Удалить локальный replay-файл?") },
        text = { Text("Файл будет удалён без возможности восстановления. Экспортированная копия не затрагивается.") },
        confirmButton = { TextButton(onClick = { onDelete(); showDeleteConfirm = false }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Отмена") } }
    )
}
