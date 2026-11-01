package org.cleargps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RadioRefinementScreen(
    beaconConsentEnabled: Boolean,
    beaconAutoEnabled: Boolean,
    openCellIdEnabled: Boolean,
    openCellIdAutoEnabled: Boolean,
    openCellIdKeyConfigured: Boolean,
    openCellIdKeyInput: String,
    beaconIntervalMinutes: Int,
    beaconMaxRequests: Int,
    openCellIdIntervalMinutes: Int,
    openCellIdMaxRequests: Int,
    openCellIdRequestsUsedToday: Int,
    isRunning: Boolean,
    runningProvider: String?,
    statusMessage: String,
    events: List<RadioRefinementEvent>,
    onBeaconConsent: (Boolean) -> Unit,
    onBeaconAutoEnabled: (Boolean) -> Unit,
    onBeaconInterval: (Int) -> Unit,
    onBeaconMaxRequests: (Int) -> Unit,
    onStartBeaconDb: () -> Unit,
    onOpenCellIdEnabled: (Boolean) -> Unit,
    onOpenCellIdAutoEnabled: (Boolean) -> Unit,
    onOpenCellIdKeyInput: (String) -> Unit,
    onSaveOpenCellIdKey: () -> Unit,
    onForgetOpenCellIdKey: () -> Unit,
    onOpenCellIdInterval: (Int) -> Unit,
    onOpenCellIdMaxRequests: (Int) -> Unit,
    onStartOpenCellId: () -> Unit,
    onStop: () -> Unit,
    onClearEvents: () -> Unit,
    embeddedInDatabase: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val screenModifier = if (embeddedInDatabase) {
        Modifier.fillMaxWidth().padding(vertical = 8.dp)
    } else {
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)
    }
    Column(
        screenModifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Уточнение радиосетей", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Поиск координат для уже имеющихся в локальной базе Wi-Fi-точек и сотовых ячеек. Ручное уточнение запускается только по нажатию; найденные координаты сохраняются отдельными удаляемыми партиями и не становятся наблюдениями.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }

        RefinementProviderCard(
            title = "BeaconDB · Wi-Fi",
            subtitle = "Сначала использует свежий совместный скан; если история урезана — восстанавливает группу BSSID одного источника с одинаковым временем последнего наблюдения, а затем пробует ближайшие точки с координатами (до 500 м).",
            icon = { Icon(Icons.Outlined.Wifi, null, tint = colors.primary) }
        ) {
            RefinementSwitchRow(
                title = "Разрешить сетевые запросы BeaconDB",
                checked = beaconConsentEnabled,
                enabled = !isRunning,
                onCheckedChange = onBeaconConsent
            )
            RefinementSwitchRow(
                title = "Автоматически уточнять новые локальные выборки",
                checked = beaconAutoEnabled,
                enabled = !isRunning,
                onCheckedChange = onBeaconAutoEnabled
            )
            Text(
                "При отключении фонового режима Clear GPS удаляет все сохранённые партии BeaconDB, включая ранее полученные вручную. Само сетевое согласие остаётся отдельным переключателем.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
            Text(
                "Для ручного запуска нужны явное сетевое согласие и подтверждённая Wi-Fi-сеть. Порядок подбора: общий недавний скан, затем сохранённые BSSID одного источника с одинаковой меткой последнего наблюдения, затем близкие точки с доверенными координатами (до 500 м). В запрос уходят только BSSID, RSSI, возраст и частота; SSID и GPS-координаты не отправляются. Неизвестный SSID не мешает lookup; явно известные скрытые сети и _nomap исключаются. Ответ BeaconDB — оценка области радиосреды, а не координата конкретного роутера.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = onStartBeaconDb,
                enabled = !isRunning && beaconConsentEnabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Refresh, null)
                Text(
                    if (runningProvider == "beacondb") "Уточнение BeaconDB…" else "Запустить BeaconDB",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }            RefinementSlider(
                label = "Пауза между запросами",
                value = beaconIntervalMinutes,
                valueLabel = "$beaconIntervalMinutes мин",
                onValueChange = onBeaconInterval
            )
            RefinementSlider(
                label = "Максимум запросов за один запуск",
                value = beaconMaxRequests,
                valueLabel = "$beaconMaxRequests из ${RadioRefinementSettings.MAX_REQUESTS_PER_RUN}",
                range = 1f..RadioRefinementSettings.MAX_REQUESTS_PER_RUN.toFloat(),
                steps = RadioRefinementSettings.MAX_REQUESTS_PER_RUN - 2,
                onValueChange = onBeaconMaxRequests
            )
            Text(
                "У BeaconDB нет опубликованного фиксированного лимита API. Поэтому Clear GPS ограничивает ручную скорость минимум одной минутой между запросами и максимум десятью запросами за один запуск.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )

        }

        RefinementProviderCard(
            title = "OpenCellID · сотовые ячейки",
            subtitle = "Ищет координаты ячеек из локальной базы по MCC/MNC/LAC или TAC/Cell ID и типу радиосети.",
            icon = { Icon(Icons.Outlined.SignalCellularAlt, null, tint = colors.primary) }
        ) {
            RefinementSwitchRow(
                title = "Разрешить запросы OpenCellID",
                checked = openCellIdEnabled,
                enabled = !isRunning,
                onCheckedChange = onOpenCellIdEnabled
            )
            RefinementSwitchRow(
                title = "Автоматически уточнять локальные сотовые точки",
                checked = openCellIdAutoEnabled,
                enabled = !isRunning,
                onCheckedChange = onOpenCellIdAutoEnabled
            )
            Text(
                "При отключении фонового режима удаляются все сохранённые партии OpenCellID, включая полученные вручную. Сетевой переключатель и ключ управляются отдельно.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
            Text(
                if (openCellIdKeyConfigured) "API-ключ сохранён на устройстве."
                else "Для поиска нужен личный API-ключ OpenCellID.",
                style = MaterialTheme.typography.bodySmall,
                color = if (openCellIdKeyConfigured) colors.primary else colors.onSurfaceVariant
            )
            OutlinedTextField(
                value = openCellIdKeyInput,
                onValueChange = onOpenCellIdKeyInput,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("API-ключ OpenCellID") },
                visualTransformation = PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = onSaveOpenCellIdKey,
                    enabled = openCellIdKeyInput.isNotBlank() && !isRunning,
                    modifier = Modifier.weight(1f)
                ) { Text("Сохранить ключ") }
                if (openCellIdKeyConfigured) {
                    OutlinedButton(onClick = onForgetOpenCellIdKey, enabled = !isRunning) {
                        Icon(Icons.Outlined.DeleteOutline, null)
                        Text("Удалить")
                    }
                }
            }
            FilledTonalButton(
                onClick = onStartOpenCellId,
                enabled = !isRunning && openCellIdEnabled && openCellIdKeyConfigured &&
                    openCellIdRequestsUsedToday < OpenCellIdLocalDbAutoLookupBudget.MAX_REQUESTS_PER_UTC_DAY,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Refresh, null)
                Text(
                    if (runningProvider == "opencellid") "Уточнение OpenCellID…" else "Запустить OpenCellID",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }            Text(
                "Лимит Clear GPS для уточнения локальной базы — не более 10 попыток за UTC-день, включая фоновое уточнение. Официальная квота OpenCellID — 1 000 API-кредитов на пользователя в сутки; сервер может ограничить запросы раньше.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Text(
                "Использовано сегодня: $openCellIdRequestsUsedToday/10 попыток Clear GPS.",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            RefinementSlider(
                label = "Пауза между запросами",
                value = openCellIdIntervalMinutes,
                valueLabel = "$openCellIdIntervalMinutes мин",
                onValueChange = onOpenCellIdInterval
            )
            RefinementSlider(
                label = "Максимум запросов за один запуск",
                value = openCellIdMaxRequests,
                valueLabel = "$openCellIdMaxRequests из ${RadioRefinementSettings.MAX_REQUESTS_PER_RUN}",
                range = 1f..RadioRefinementSettings.MAX_REQUESTS_PER_RUN.toFloat(),
                steps = RadioRefinementSettings.MAX_REQUESTS_PER_RUN - 2,
                onValueChange = onOpenCellIdMaxRequests
            )

        }

        if (isRunning) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = colors.secondaryContainer
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Выполняется: ${if (runningProvider == "beacondb") "BeaconDB" else "OpenCellID"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                    TextButton(onClick = onStop) { Text("Остановить") }
                }
            }
        }

        if (statusMessage.isNotBlank()) {
            Text(statusMessage, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = colors.surfaceContainerHigh
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Журнал уточнения",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onClearEvents, enabled = events.isNotEmpty() && !isRunning) {
                        Text("Очистить")
                    }
                }
                Text(
                    "Здесь отображается, что сервис нашёл, какие точки уточнил, что не нашёл и почему запрос был пропущен. Записи хранятся локально; радиоидентификаторы и API-ключи в журнал не попадают.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Divider(color = colors.outlineVariant)
                if (events.isEmpty()) {
                    Text("Пока нет событий уточнения.", color = colors.onSurfaceVariant)
                } else {
                    events.take(80).forEach { event ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val statusColor = when (event.status) {
                                    "success" -> colors.primary
                                    "warning", "error" -> colors.error
                                    else -> colors.onSurfaceVariant
                                }
                                Text(
                                    event.provider,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = statusColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
                                        .format(Date(event.timestampMillis)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Text(event.message, style = MaterialTheme.typography.bodySmall)
                        }
                        Divider(color = colors.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun RefinementProviderCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = colors.surfaceContainerHigh
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceContainer) {
                    androidx.compose.foundation.layout.Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        icon()
                    }
                }
                Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            Divider(color = colors.outlineVariant)
            content()
        }
    }
}

@Composable
private fun RefinementSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun RefinementSlider(
    label: String,
    value: Int,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float> = RadioRefinementSettings.MIN_INTERVAL_MINUTES.toFloat()..RadioRefinementSettings.MAX_INTERVAL_MINUTES.toFloat(),
    steps: Int = RadioRefinementSettings.MAX_INTERVAL_MINUTES - 2,
    onValueChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat().coerceIn(range.start, range.endInclusive),
            onValueChange = { onValueChange(it.toInt().coerceIn(range.start.toInt(), range.endInclusive.toInt())) },
            valueRange = range,
            steps = steps
        )
    }
}
