package org.cleargps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun Obd2Screen(
    adapters: List<Obd2Adapter>,
    selectedAddress: String,
    connected: Boolean,
    connecting: Boolean,
    telemetry: Obd2Telemetry,
    status: String,
    statusIsError: Boolean,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("OBD-II · только чтение", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Bluetooth Classic SPP · без записи в ЭБУ",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            AssistChip(
                onClick = { },
                enabled = false,
                label = { Text(if (connected) "Подключено" else "Отключено") },
                leadingIcon = { Icon(Icons.Outlined.DirectionsCar, null, modifier = Modifier.size(17.dp)) }
            )
        }

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bluetooth, null, tint = colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Сопряжённые Bluetooth-устройства", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Сканирования эфира нет. Выбери свой OBD-II/ELM327 из списка ранее сопряжённых Bluetooth-устройств; прочие устройства могут не поддерживать SPP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                if (adapters.isEmpty()) {
                    Text(
                        "Адаптер не найден. Сначала включи Bluetooth и выполни сопряжение с OBD-II адаптером в системных настройках.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    adapters.forEach { adapter ->
                        val selected = selectedAddress.equals(adapter.address, ignoreCase = true)
                        Surface(
                            Modifier.fillMaxWidth().clickable(enabled = !connected && !connecting) {
                                onSelect(adapter.address)
                            },
                            shape = RoundedCornerShape(13.dp),
                            color = if (selected) colors.secondaryContainer else colors.surfaceContainer
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        adapter.name,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(adapter.address, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                }
                                RadioButton(
                                    selected = selected,
                                    enabled = !connected && !connecting,
                                    onClick = { onSelect(adapter.address) }
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !connecting,
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Outlined.Refresh, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Обновить")
                    }
                    OutlinedButton(
                        onClick = onOpenBluetoothSettings,
                        modifier = Modifier.weight(1f).heightIn(min = 46.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Outlined.Bluetooth, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Bluetooth")
                    }
                }
                if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (connected) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Отключить адаптер") }
                } else {
                    FilledTonalButton(
                        onClick = onConnect,
                        enabled = selectedAddress.isNotBlank() && !connecting && adapters.any {
                            it.address.equals(selectedAddress, ignoreCase = true)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Outlined.Bluetooth, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (connecting) "Подключение…" else "Подключить и читать параметры")
                    }
                }
            }
        }

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DirectionsCar, null, tint = colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Данные автомобиля", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (statusIsError) {
                    Surface(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = colors.errorContainer
                    ) {
                        Row(
                            Modifier.padding(11.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Outlined.Shield, null, tint = colors.onErrorContainer)
                            Spacer(Modifier.width(8.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Скорость OBD-II недоступна",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onErrorContainer
                                )
                                Text(
                                    status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onErrorContainer
                                )
                            }
                        }
                    }
                } else {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    VehicleMetric(
                        title = "Скорость PID 0D",
                        value = telemetry.vehicleSpeedKmh?.toString() ?: "—",
                        unit = "км/ч",
                        modifier = Modifier.weight(1f)
                    )
                    VehicleMetric(
                        title = "Обороты PID 0C",
                        value = telemetry.engineRpm?.toString() ?: "—",
                        unit = "об/мин",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    VehicleMetric(
                        title = "Нагрузка PID 04",
                        value = telemetry.engineLoadPercent?.toString() ?: "—",
                        unit = "%",
                        modifier = Modifier.weight(1f)
                    )
                    VehicleMetric(
                        title = "Дроссель PID 11",
                        value = telemetry.throttlePercent?.toString() ?: "—",
                        unit = "%",
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    VehicleMetric(
                        title = "MAP PID 0B",
                        value = telemetry.manifoldPressureKpa?.toString() ?: "—",
                        unit = "кПа",
                        modifier = Modifier.weight(1f)
                    )
                    VehicleMetric(
                        title = "Температура PID 05",
                        value = telemetry.coolantTemperatureCelsius?.toString() ?: "—",
                        unit = "°C",
                        modifier = Modifier.weight(1f)
                    )
                }
                VehicleMetric(
                    title = "Барометрическое давление PID 33",
                    value = telemetry.barometricPressureKpa?.toString() ?: "—",
                    unit = "кПа",
                    modifier = Modifier.fillMaxWidth()
                )
                val unavailablePids = telemetry.pidIssues.filterValues {
                    it == Obd2PidIssue.NO_VALID_RESPONSE
                }.keys
                val unsupportedPids = telemetry.pidIssues.filterValues {
                    it == Obd2PidIssue.PID_UNSUPPORTED
                }.keys
                val disabledPids = telemetry.pidIssues.filterValues {
                    it == Obd2PidIssue.PID_DISABLED_FOR_SESSION
                }.keys
                if (unsupportedPids.isNotEmpty()) {
                    Text(
                        "ЭБУ не объявил поддержку: " + unsupportedPids.sorted().joinToString { Obd2Protocol.pidLabel(it) },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
                if (unavailablePids.isNotEmpty()) {
                    Text(
                        "Некорректный ответ: " + unavailablePids.sorted().joinToString { Obd2Protocol.pidLabel(it) },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.error
                    )
                }
                if (disabledPids.isNotEmpty()) {
                    Text(
                        "Отключены до переподключения: " + disabledPids.sorted().joinToString { Obd2Protocol.pidLabel(it) },
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.error
                    )
                }
                if (telemetry.updatedAt > 0L) {
                    Text(
                        "Последняя попытка: " +
                            java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(telemetry.updatedAt)) +
                            if (telemetry.vehicleSpeedKmh != null) " · скорость получена" else " · скорость не получена",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surfaceContainer) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, null, tint = colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Гарантии режима чтения", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Сначала читаются битовые карты поддержки SAE J1979 (00/20/40/60/80/A0/C0), затем только объявленные ЭБУ стандартные PID 04, 05, 0B, 0C, 0D, 11 и 33. Если карта возможностей недоступна, приложение безопасно ограничивается проверкой скорости 0D. VIN, Mode 09 и произвольные команды не запрашиваются.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Скорость используется только как вспомогательный сигнал. Прогноз GPS ограничен 30 секундами и 300 м, требует достоверного GPS-курса, точности не хуже 100 м и скорости не старше 5 секунд. При ошибке или устаревании значение сразу исключается. В фоновом сервисе прогноз передаётся другим приложениям только при явно включённом mock-выходе, нормальном состоянии GNSS-фильтра и вне зон риска. ATAT1 / ATSP0 настраивают только адаптер; команд записи, кодирования, сброса ошибок и адаптаций нет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Text(
                    "Поддерживаются уже сопряжённые Bluetooth Classic SPP-адаптеры (обычно ELM327-совместимые); BLE и USB пока не поддерживаются. Три подряд завершённых, но некорректных ответа отключают только соответствующий PID до переподключения. Таймаут или потеря Bluetooth-соединения завершают текущую сессию, чтобы не использовать потенциально рассинхронизированный поток. Дополнительные параметры выводятся только на этом экране и не сохраняются в историю; для прогноза координат используется только свежая скорость 0D. Старые данные скорости очищаются, а выбранный GPS/радио источник продолжает работать независимо от OBD-II.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VehicleMetric(title: String, value: String, unit: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(13.dp), color = colors.surfaceContainer) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(unit, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}