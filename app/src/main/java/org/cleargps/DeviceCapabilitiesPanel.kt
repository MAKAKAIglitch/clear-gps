package org.cleargps

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class DeviceCapabilityItem(
    val title: String,
    val status: String,
    val detail: String,
    val supported: Boolean?
)

private data class DeviceCapabilityReport(
    val androidVersion: String,
    val apiLevel: Int,
    val items: List<DeviceCapabilityItem>
)

@Composable
internal fun DeviceCapabilitiesPanel() {
    val context = LocalContext.current
    var report by remember(context) { mutableStateOf(readDeviceCapabilityReport(context)) }
    val colors = MaterialTheme.colorScheme

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Возможности устройства",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "Android ${report.androidVersion} · API ${report.apiLevel}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant
        )
        Text(
            "Проверка локальная: сеть и радиосканирование не запускаются. Статические свойства показывают заявленные возможности; для GNSS используются только свежие данные, уже полученные работающим сервисом.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant
        )
        OutlinedButton(
            onClick = { report = readDeviceCapabilityReport(context) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("Обновить отчёт")
        }
        report.items.forEach { item ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier.padding(11.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        item.status,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = when (item.supported) {
                            true -> colors.primary
                            false -> colors.onSurfaceVariant
                            null -> colors.secondary
                        }
                    )
                    Text(
                        item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            "Неподтверждённая возможность отображается как неизвестная, а не как неисправность. Наличие датчика не означает, что его данные уже участвуют в live-слиянии позиции.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant
        )
    }
}

private fun readDeviceCapabilityReport(context: Context): DeviceCapabilityReport {
    val packageManager = context.packageManager
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    val nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    val gnssSnapshot = runCatching {
        GnssDiagnosticsLiveStore.snapshot(nowElapsedRealtimeNanos)
    }.getOrNull()

    val hasGpsProvider = runCatching {
        locationManager?.allProviders?.contains(LocationManager.GPS_PROVIDER) == true
    }.getOrDefault(false)
    val gpsEnabled = if (hasGpsProvider) runCatching {
        locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }.getOrNull() else false

    val rawMeasurementsFresh = gnssSnapshot?.measurementsElapsedRealtimeNanos != null
    val agcSatelliteCount = gnssSnapshot?.satellites?.count { it.agcDb != null } ?: 0
    val agcBandCount = gnssSnapshot?.agcBands?.size ?: 0
    val observedBands = GnssFrequencyBandPolicy.observedBands(
        gnssSnapshot?.satellites.orEmpty().mapNotNull {
            it.reportedCarrierFrequencyHz ?: it.measurementCarrierFrequencyHz
        }
    )

    val wifiRttSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && runCatching {
        packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)
    }.getOrDefault(false)
    val wifiRttAvailable = if (wifiRttSupported) runCatching {
        (context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager)?.isAvailable
    }.getOrNull() else false

    fun hasSensor(type: Int): Boolean = runCatching {
        sensorManager?.getDefaultSensor(type) != null
    }.getOrDefault(false)

    val rawGnssItem = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.N -> DeviceCapabilityItem(
            "Сырые GNSS-измерения",
            "API недоступен",
            "На этой версии Android нет API raw GNSS, используемого детектором.",
            false
        )
        rawMeasurementsFresh -> DeviceCapabilityItem(
            "Сырые GNSS-измерения",
            "Измерения получены недавно",
            "Сервис уже получил свежие измерения приёмника. Для текущего статуса обнови отчёт после включения GPS/Авто.",
            true
        )
        else -> DeviceCapabilityItem(
            "Сырые GNSS-измерения",
            "Пока не подтверждено",
            "API Android доступен, но свежий поток измерений не наблюдался. Включи мониторинг в GPS/Авто и обнови отчёт; отсутствие данных само по себе не доказывает отсутствие поддержки.",
            null
        )
    }

    val agcItem = when {
        agcSatelliteCount > 0 || agcBandCount > 0 -> DeviceCapabilityItem(
            "AGC",
            "Данные доступны",
            "Текущий GNSS-снимок содержит AGC для ${agcSatelliteCount} спутников и ${agcBandCount} диапазонов. Детектор может использовать только фактически предоставленные значения.",
            true
        )
        rawMeasurementsFresh -> DeviceCapabilityItem(
            "AGC",
            "В свежих измерениях не сообщается",
            "Приёмник не передал пригодные значения AGC в текущем снимке. Это отсутствие измерения, а не доказательство глушения или спуфинга.",
            null
        )
        else -> DeviceCapabilityItem(
            "AGC",
            "Пока неизвестно",
            "Проверь снова при активном GNSS-мониторинге. Некоторые устройства и API не предоставляют AGC.",
            null
        )
    }

    val multiFrequencyItem = when {
        GnssFrequencyBandPolicy.indicatesMultiFrequency(observedBands) -> DeviceCapabilityItem(
            "Несколько GNSS-частотных диапазонов",
            "Наблюдались: ${observedBands.map { it.label }.sorted().joinToString()}",
            "Это подтверждает приём сигналов в нескольких диапазонах в текущем снимке, но не доказывает двухчастотную поддержку каждого созвездия.",
            true
        )
        observedBands.size == 1 -> DeviceCapabilityItem(
            "Несколько GNSS-частотных диапазонов",
            "Наблюдался только один диапазон: ${observedBands.first().label}",
            "Второй диапазон пока не подтверждён. Старый чипсет, условия приёма и состав видимых созвездий могут влиять на данные; это не признак атаки.",
            null
        )
        else -> DeviceCapabilityItem(
            "Несколько GNSS-частотных диапазонов",
            "Пока неизвестно",
            "Приёмник не сообщил свежие частоты, достаточные для проверки. Отсутствие L5/E5 не считается признаком спуфинга.",
            null
        )
    }

    val rttItem = when {
        !wifiRttSupported -> DeviceCapabilityItem(
            "Wi-Fi RTT",
            "Система не заявляет поддержку",
            "RTT-измерения на этом устройстве не доступны по объявленным возможностям Android. Обычный Wi-Fi fingerprint не зависит от RTT.",
            false
        )
        wifiRttAvailable == true -> DeviceCapabilityItem(
            "Wi-Fi RTT",
            "Оборудование заявлено, сервис сейчас доступен",
            "В текущей ветке RTT-измерения ещё не используются для определения позиции; наличие функции не означает, что RTT уже включён в алгоритм.",
            true
        )
        else -> DeviceCapabilityItem(
            "Wi-Fi RTT",
            "Оборудование заявлено, но сервис сейчас недоступен",
            "Доступность может зависеть от состояния Wi-Fi и системы. Фактический RTT-запрос не запускался.",
            null
        )
    }

    val barometer = hasSensor(Sensor.TYPE_PRESSURE)
    val pressureSample = BarometerPressureLiveStore.snapshot(nowElapsedRealtimeNanos)
    val accelerometer = hasSensor(Sensor.TYPE_ACCELEROMETER)
    val gyroscope = hasSensor(Sensor.TYPE_GYROSCOPE)
    val magnetometer = hasSensor(Sensor.TYPE_MAGNETIC_FIELD)

    return DeviceCapabilityReport(
        androidVersion = Build.VERSION.RELEASE ?: "неизвестно",
        apiLevel = Build.VERSION.SDK_INT,
        items = listOf(
            DeviceCapabilityItem(
                "GPS-провайдер",
                when (gpsEnabled) {
                    true -> "Доступен и включён"
                    false -> if (hasGpsProvider) "Есть, но выключен в системных настройках" else "Не обнаружен"
                    null -> "Состояние неизвестно"
                },
                "Отчёт проверяет системный провайдер, но не запускает запрос геолокации и не начинает мониторинг.",
                gpsEnabled
            ),
            rawGnssItem,
            agcItem,
            multiFrequencyItem,
            DeviceCapabilityItem(
                "Барометр",
                when {
                    !barometer -> "Датчик не обнаружен"
                    pressureSample != null -> "Датчик обнаружен; показания свежие"
                    else -> "Датчик обнаружен; свежих показаний сейчас нет"
                },
                when {
                    !barometer -> "Барометрическая часть оценки высоты недоступна; отсутствие датчика не повышает риск GNSS."
                    pressureSample != null -> "Последнее показание " +
                        String.format(java.util.Locale.ROOT, "%.2f", pressureSample.pressurePascals / 100.0) +
                        " hPa, возраст " +
                        ((nowElapsedRealtimeNanos - pressureSample.elapsedRealtimeNanos) / 1_000_000_000L) +
                        " с. Считывание запускается только во время мониторинга; высота пока не входит в live TrustEngine."
                    else -> "Датчик доступен, но свежих измерений нет. Запустите мониторинг, чтобы временно читать давление; данные остаются только в памяти и очищаются при остановке."
                },
                when {
                    !barometer -> false
                    pressureSample != null -> true
                    else -> null
                }
            ),
            rttItem,
            DeviceCapabilityItem(
                "Акселерометр",
                if (accelerometer) "Датчик обнаружен" else "Датчик не обнаружен",
                if (accelerometer) "Наличие подтверждено; live fusion IMU с позицией пока не реализован."
                else "Счисление движения по акселерометру недоступно.",
                accelerometer
            ),
            DeviceCapabilityItem(
                "Гироскоп",
                if (gyroscope) "Датчик обнаружен" else "Датчик не обнаружен",
                if (gyroscope) "Наличие подтверждено; live fusion IMU с позицией пока не реализован."
                else "Измерение вращения по гироскопу недоступно.",
                gyroscope
            ),
            DeviceCapabilityItem(
                "Магнитометр",
                if (magnetometer) "Датчик обнаружен" else "Датчик не обнаружен",
                if (magnetometer) "Датчик есть; курс GNSS с магнитным курсом в текущем алгоритме не объединяется."
                else "Магнитный курс недоступен.",
                magnetometer
            )
        )
    )
}
