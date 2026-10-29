package org.cleargps

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun Obd2Screen(
    adapters: List<Obd2Adapter>,
    selectedAddress: String,
    connected: Boolean,
    connecting: Boolean,
    scanningBle: Boolean,
    telemetry: Obd2Telemetry,
    calibration: Obd2SpeedCalibrationSnapshot,
    gearLearning: Obd2GearLearningSnapshot,
    driveMetrics: Obd2DriveMetricsSnapshot,
    status: String,
    statusIsError: Boolean,
    externalBroadcastEnabled: Boolean,
    onExternalBroadcastEnabledChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onScanBle: () -> Unit,
    onAddWifi: (String, String) -> Unit,
    onSelect: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenBluetoothSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var wifiHost by remember { mutableStateOf("192.168.0.10") }
    var wifiPort by remember { mutableStateOf(Obd2AdapterSelection.DEFAULT_WIFI_PORT.toString()) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("OBD-II · только чтение", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Bluetooth Classic SPP + BLE GATT + Wi-Fi TCP · без записи в ЭБУ",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            AssistChip(
                onClick = { },
                enabled = false,
                label = { Text(when { connected -> "Подключено"; connecting -> "Подключение"; else -> "Отключено" }) },
                leadingIcon = { Icon(Icons.Outlined.DirectionsCar, null, modifier = Modifier.size(17.dp)) }
            )
        }

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bluetooth, null, tint = colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("OBD-II адаптеры", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Classic SPP берётся только из сопряжённых устройств. BLE-поиск запускается только вручную. Для Wi-Fi OBD укажи локальный адрес и порт после подключения к точке доступа адаптера в настройках Android.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                if (adapters.isEmpty()) {
                    Text(
                        "Пока список пуст. Можно сопрячь Classic SPP, найти BLE OBD кнопкой поиска или вручную добавить Wi-Fi OBD ниже.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    adapters.forEach { adapter ->
                        val selected = selectedAddress.equals(adapter.selectionKey, ignoreCase = true)
                        Surface(
                            Modifier.fillMaxWidth().clickable(enabled = !connected && !connecting) {
                                onSelect(adapter.selectionKey)
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
                                    Text(adapter.transportType.displayName + " · " + adapter.address, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                }
                                RadioButton(
                                    selected = selected,
                                    enabled = !connected && !connecting,
                                    onClick = { onSelect(adapter.selectionKey) }
                                )
                            }
                        }
                    }
                }
                OutlinedButton(
                    onClick = onScanBle,
                    enabled = !connected && !connecting && !scanningBle,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Outlined.Bluetooth, null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (scanningBle) "Поиск BLE OBD…" else "Найти BLE OBD (8 секунд)")
                }
                if (scanningBle) LinearProgressIndicator(Modifier.fillMaxWidth())

                Text("Wi-Fi OBD · ручное подключение", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Сначала подключись к Wi-Fi сети адаптера через настройки Android. Clear GPS не переключает сеть автоматически; интернет на сети адаптера может отсутствовать. Ввод разрешён только для приватного IPv4 и TCP-порта.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                OutlinedTextField(
                    value = wifiHost,
                    onValueChange = { wifiHost = it.trim().take(15) },
                    label = { Text("Локальный IPv4") },
                    placeholder = { Text("192.168.0.10") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = wifiPort,
                    onValueChange = { wifiPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("TCP-порт") },
                    placeholder = { Text("35000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { onAddWifi(wifiHost.trim(), wifiPort.trim()) },
                    enabled = !connected && !connecting && !scanningBle,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Добавить Wi-Fi OBD") }

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
                if (connected || connecting) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (connecting) "Отменить подключение" else "Отключить адаптер") }
                } else {
                    FilledTonalButton(
                        onClick = onConnect,
                        enabled = selectedAddress.isNotBlank() && !connecting && adapters.any {
                            it.selectionKey.equals(selectedAddress, ignoreCase = true)
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
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "VIN · только в памяти",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        telemetry.vehicleVin ?: when (telemetry.vinIssue) {
                            Obd2VinIssue.INFO_TYPE_UNSUPPORTED -> "ЭБУ не объявил поддержку VIN"
                            Obd2VinIssue.NO_VALID_RESPONSE -> "VIN не получен или ответ некорректен"
                            null -> if (connected) "Проверка VIN…" else "—"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (telemetry.vehicleVin != null) FontWeight.SemiBold else FontWeight.Normal
                    )
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
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    color = colors.surfaceContainer
                ) {
                    Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Движение за текущее подключение", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            VehicleMetric(
                                title = "Расчётный путь",
                                value = String.format(Locale.ROOT, "%.2f", driveMetrics.sessionDistanceMeters / 1_000.0),
                                unit = "км",
                                modifier = Modifier.weight(1f)
                            )
                            VehicleMetric(
                                title = "Ускорение",
                                value = driveMetrics.longitudinalAccelerationMps2?.let { String.format(Locale.ROOT, "%+.1f", it) } ?: "—",
                                unit = "м/с²",
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Text(
                            "Принятых отсчётов скорости: ${driveMetrics.acceptedSpeedSamples}. Неизвестные интервалы не заполняются догадками.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                        Text(
                            "Это оценка пути за подключение, а не одометр или история поездки. Данные не сохраняются и не влияют на риск спуфинга.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    color = colors.surfaceContainer
                ) {
                    Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Сверка скорости GNSS ↔ OBD-II", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (calibration.status) {
                                Obd2CalibrationStatus.WAITING_FOR_DATA -> "Жду свежую пару скоростей с проверяемыми метками времени."
                                Obd2CalibrationStatus.WAITING_FOR_TRUSTED_GNSS -> "Обучение разрешено только при состоянии доверия NORMAL; сейчас коэффициент не меняется."
                                Obd2CalibrationStatus.WAITING_FOR_PHYSICAL_GNSS -> "Нужен физический GPS-фикс. Сетевые и mock-координаты не используются."
                                Obd2CalibrationStatus.LOW_GNSS_QUALITY -> "Качества GPS недостаточно: точность должна быть до 20 м, а скорость — пригодна для сравнения."
                                Obd2CalibrationStatus.TIME_MISMATCH -> "Измерения устарели или слишком далеко по времени; пара исключена."
                                Obd2CalibrationStatus.OBD_QUERY_TOO_SLOW -> "Ответ OBD пришёл слишком медленно; пара исключена из калибровки."
                                Obd2CalibrationStatus.WAITING_FOR_MOVEMENT -> "Нужны согласованные скорости в движении примерно от 8 до 160 км/ч."
                                Obd2CalibrationStatus.COLLECTING -> "Набираю выборку. До завершения коэффициент только предлагается и не применяется."
                                Obd2CalibrationStatus.SPEEDS_DIVERGE -> "Скорости заметно расходятся: эта пара не обучает коэффициент. Само расхождение не доказывает спуфинг."
                                Obd2CalibrationStatus.CALIBRATED -> "Поправка проверена на повторяющихся согласованных измерениях и действует только в текущем соединении."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        Text(
                            "Принятых пар в окне: ${calibration.acceptedPairs} из ${Obd2SpeedCalibration.MAX_SAMPLE_WINDOW}. " +
                                "Для включения нужно минимум ${Obd2SpeedCalibration.MIN_CALIBRATION_SAMPLES} пар, 30 секунд и диапазон скоростей.",
                            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
                        )
                        calibration.differenceKmh?.let { difference ->
                            Text("GNSS − OBD: " + String.format(Locale.ROOT, "%+.1f км/ч", difference), style = MaterialTheme.typography.labelMedium)
                        }
                        calibration.candidateScale?.takeIf { calibration.activeCorrectionScale == null }?.let { scale ->
                            Text(
                                "Предварительный коэффициент: " + String.format(Locale.ROOT, "×%.3f", scale) + " · пока не применяется",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        calibration.activeCorrectionScale?.let { scale ->
                            Text(
                                "Поправка для счисления пути: " + String.format(Locale.ROOT, "×%.3f", scale) + " · только текущий сеанс",
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = colors.primary
                            )
                        }
                    }
                }
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    color = colors.surfaceContainer
                ) {
                    Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Наблюдение передач по RPM/скорости", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (gearLearning.status) {
                                Obd2GearLearningStatus.WAITING_FOR_DATA -> "Нужны свежие ответы PID 0C и 0D с измеренными временными интервалами."
                                Obd2GearLearningStatus.WAITING_FOR_TRUSTED_SPEED_PAIR -> "Учёт разрешён только для согласованной пары при NORMAL и физическом GPS; сейчас новые отношения не добавляются."
                                Obd2GearLearningStatus.TIME_MISMATCH -> "Временные отметки RPM и скорости не подходят для сравнения; пара пропущена."
                                Obd2GearLearningStatus.WAITING_FOR_STEADY_DRIVING -> "Ожидаю ровное движение без сильного газа/нагрузки и переключения."
                                Obd2GearLearningStatus.LEARNING -> "Наблюдаю устойчивые отношения RPM/скорость. Число передачи не угадывается."
                                Obd2GearLearningStatus.RATIOS_LEARNED -> "Найдено несколько устойчивых диапазонов. Это относительные отношения, а не точные номера передач."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        gearLearning.currentRatioRpmPerKmh?.let { ratio ->
                            Text(
                                "Текущий RPM/скорость: " + String.format(Locale.ROOT, "%.1f об/мин на км/ч", ratio),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (gearLearning.bands.isEmpty()) {
                            Text(
                                "Подтверждённых диапазонов пока нет. Нужны минимум ${Obd2GearRatioLearner.MIN_BAND_SAMPLES} согласованных отсчётов на диапазон.",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        } else {
                            gearLearning.bands.forEachIndexed { index, band ->
                                Text(
                                    "Диапазон ${index + 1}: " +
                                        String.format(Locale.ROOT, "%.1f об/мин на км/ч", band.medianRpmPerKmh) +
                                        " · ${band.samples} отсч.",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        Text(
                            "Отношения остаются в памяти текущего соединения. Без размера шин и главной пары нельзя надёжно назначить реальные номера передач; на риск спуфинга они не влияют.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                val unavailablePids = telemetry.pidIssues.filterValues {
                    it == Obd2PidIssue.NO_VALID_RESPONSE
                }.keys
                val suppressedZeroPids = telemetry.pidIssues.filterValues {
                    it == Obd2PidIssue.TRANSIENT_ZERO_SUPPRESSED
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
                if (suppressedZeroPids.isNotEmpty()) {
                    Text(
                        "Нулевые ответы временно скрыты до трёх последовательных подтверждений: " +
                            suppressedZeroPids.sorted().joinToString { Obd2Protocol.pidLabel(it) } +
                            ". Подтверждённый ноль появится в данных; неподтверждённый не передаётся как скорость/обороты.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.tertiary
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

        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = if (externalBroadcastEnabled) colors.primaryContainer else colors.surfaceContainer
        ) {
            Row(
                Modifier.fillMaxWidth().padding(13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        "Передача OBD-II в другие приложения",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (externalBroadcastEnabled) colors.onPrimaryContainer else colors.onSurface
                    )
                    Text(
                        if (externalBroadcastEnabled) {
                            "Включена. Доступные свежие PID отправляются через Android broadcast."
                        } else {
                            "Выключена по умолчанию. Включи её, чтобы разрешить другим приложениям получать свежие значения."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (externalBroadcastEnabled) colors.onPrimaryContainer else colors.onSurfaceVariant
                    )
                    Text(
                        "Формат: ${Obd2ExternalTelemetryBroadcast.ACTION_TELEMETRY}. Получатель должен подписаться на этот action (динамический exported BroadcastReceiver). Неподтверждённые нули и устаревшие поля не передаются; VIN и адрес адаптера не передаются.",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (externalBroadcastEnabled) colors.onPrimaryContainer else colors.onSurfaceVariant
                    )
                }
                Switch(
                    checked = externalBroadcastEnabled,
                    onCheckedChange = onExternalBroadcastEnabledChange
                )
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
                    "Сначала читаются битовые карты поддержки SAE J1979 (00/20/40/60/80/A0/C0), затем только объявленные ЭБУ стандартные PID 04, 05, 0B, 0C, 0D, 11 и 33. Если карта возможностей недоступна, приложение безопасно ограничивается проверкой скорости 0D. VIN запрашивается только после проверки поддержки Mode 09; произвольные команды не допускаются.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Скорость используется только как вспомогательный сигнал. Прогноз GPS ограничен 30 секундами и 300 м, требует достоверного GPS-курса, точности не хуже 100 м и скорости не старше 5 секунд. При ошибке или устаревании значение сразу исключается. В фоновом сервисе прогноз передаётся другим приложениям только при явно включённом mock-выходе, нормальном состоянии GNSS-фильтра и вне зон риска. ATAT1 / ATSP0 настраивают только адаптер; команд записи, кодирования, сброса ошибок и адаптаций нет.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Text(
                    "Поддерживаются Bluetooth Classic SPP и BLE-кандидаты, найденные явным сканированием; совместимость BLE GATT зависит от схемы характеристик адаптера. USB пока не поддерживается. Три подряд завершённых, но некорректных ответа отключают только соответствующий PID до переподключения. Таймаут или потеря Bluetooth-соединения завершают текущую сессию, чтобы не использовать потенциально рассинхронизированный поток. Дополнительные параметры выводятся только на этом экране и не сохраняются в историю; для прогноза координат используется только свежая скорость 0D. Старые данные скорости очищаются, а выбранный GPS/радио источник продолжает работать независимо от OBD-II.",
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