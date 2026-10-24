package org.cleargps

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cleargps.core.FilterState
import org.cleargps.core.ZonePoint
import org.cleargps.core.ZoneStore
import org.maplibre.android.maps.MapView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class PendingAction { NONE, REFRESH, CENTER, MONITOR, MOCK }
private enum class LookupPhase { IDLE, SCANNING, UPDATED, CACHED, NO_SIGNALS, ERROR }

@Composable
fun ClearGpsApp(onMapViewCreated: (MapView) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE) }
    val repository = remember { RadioGeolocationRepository(context.applicationContext) }
    val mapController = remember { ClearGpsMapController() }

    var liveLocation by remember { mutableStateOf<Location?>(null) }
    var radioEstimate by remember { mutableStateOf(RadioGeolocationRepository.loadLastEstimate(context)) }
    var manualAnchor by remember { mutableStateOf(ManualLocationStore.load(context)) }
    var savedZone by remember { mutableStateOf(ZoneStore.load(context)) }
    var draftZone by remember { mutableStateOf(emptyList<ZonePoint>()) }
    var mapTapMode by remember { mutableStateOf(MapTapMode.NONE) }
    var permissionRevision by remember { mutableStateOf(0) }

    var lookupPhase by remember { mutableStateOf(LookupPhase.IDLE) }
    var lookupMessage by remember { mutableStateOf("Подготовь источники или обнови оценку местоположения.") }
    var lookupError by remember { mutableStateOf(false) }
    var lookupBusy by remember { mutableStateOf(false) }
    var wifiCount by remember { mutableStateOf(0) }
    var cellCount by remember { mutableStateOf(0) }
    var beaconCount by remember { mutableStateOf(0) }
    var fromCache by remember { mutableStateOf(false) }

    var monitoring by remember { mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)) }
    var mockEnabled by remember { mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)) }
    var filterState by remember { mutableStateOf(prefs.getString(LocationMonitorService.KEY_STATE, FilterState.WAITING.name) ?: FilterState.WAITING.name) }
    var riskScore by remember { mutableStateOf(prefs.getInt(LocationMonitorService.KEY_SCORE, 0)) }
    var inRiskZone by remember { mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_IN_ZONE, false)) }
    var serviceStatus by remember { mutableStateOf(prefs.getString(LocationMonitorService.KEY_SERVICE_STATUS, "") ?: "") }

    var pendingAction by remember { mutableStateOf(PendingAction.NONE) }
    var pendingForce by remember { mutableStateOf(false) }
    var showConsentDialog by remember { mutableStateOf(false) }
    var consentForce by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var dailyRefresh by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_DAILY_REFRESH, true)) }

    val hasLocationPermission = hasLocationPermission(context)
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    val runRefresh: (Boolean) -> Unit = { force ->
        if (!lookupBusy) {
            scope.launch {
                lookupBusy = true
                lookupPhase = LookupPhase.SCANNING
                lookupError = false
                fromCache = false
                lookupMessage = "Сканирую доступные радиосети и сверяю их с BeaconDB…"
                try {
                    val result = repository.locate(force)
                    wifiCount = result.wifiCount
                    cellCount = result.cellCount
                    beaconCount = result.beaconCount
                    fromCache = result.fromCache
                    result.estimate?.let { radioEstimate = it }
                    lookupMessage = result.message
                    lookupError = result.estimate == null && !result.fromCache
                    lookupPhase = when {
                        result.fromCache -> LookupPhase.CACHED
                        result.estimate != null -> LookupPhase.UPDATED
                        result.wifiCount + result.cellCount + result.beaconCount == 0 -> LookupPhase.NO_SIGNALS
                        else -> LookupPhase.ERROR
                    }
                } catch (error: SecurityException) {
                    lookupMessage = "Android не разрешил сканирование. Проверь разрешения для Wi‑Fi, местоположения и Bluetooth."
                    lookupPhase = LookupPhase.ERROR
                    lookupError = true
                } catch (error: Exception) {
                    lookupMessage = "Не удалось обновить источники: " + (error.message ?: "неизвестная ошибка")
                    lookupPhase = LookupPhase.ERROR
                    lookupError = true
                } finally {
                    lookupBusy = false
                    radioEstimate = RadioGeolocationRepository.loadLastEstimate(context)
                }
            }
        }
    }

    val runMonitoring: () -> Unit = {
        val intent = Intent(context, LocationMonitorService::class.java)
            .setAction(LocationMonitorService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        prefs.edit().putBoolean(LocationMonitorService.KEY_MONITORING, true).apply()
        monitoring = true
        serviceStatus = "Мониторинг запущен"
    }

    val runStopMonitoring: () -> Unit = {
        val intent = Intent(context, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_STOP)
        context.startService(intent)
        prefs.edit().putBoolean(LocationMonitorService.KEY_MONITORING, false).apply()
        monitoring = false
        serviceStatus = "Мониторинг остановлен"
    }

    val runMock: () -> Unit = {
        val intent = Intent(context, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        prefs.edit().putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, true).apply()
        mockEnabled = true
        Toast.makeText(context, "Чтобы выдать фиктивные координаты, выбери Clear GPS в настройках разработчика → Приложение для фиктивных местоположений.", Toast.LENGTH_LONG).show()
    }

    val runDisableMock: () -> Unit = {
        val intent = Intent(context, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_DISABLE_MOCK)
        context.startService(intent)
        prefs.edit().putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false).apply()
        mockEnabled = false
        serviceStatus = "Выдача фиктивных координат выключена"
    }

    val executeAction: (PendingAction, Boolean) -> Unit = { action, force ->
        when (action) {
            PendingAction.REFRESH -> runRefresh(force)
            PendingAction.CENTER -> mapController.focusPrimary()
            PendingAction.MONITOR -> runMonitoring()
            PendingAction.MOCK -> runMock()
            PendingAction.NONE -> Unit
        }
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRevision++
        val action = pendingAction
        val force = pendingForce
        pendingAction = PendingAction.NONE
        executeAction(action, force)
    }

    val ensureRadioPermissions: (PendingAction, Boolean) -> Unit = { action, force ->
        if (action == PendingAction.REFRESH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
            pendingAction = action
            pendingForce = force
            bluetoothPermissionLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ))
        } else {
            executeAction(action, force)
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionRevision++
        val action = pendingAction
        val force = pendingForce
        if (result.values.any { it }) {
            ensureRadioPermissions(action, force)
        } else {
            pendingAction = PendingAction.NONE
            lookupMessage = "Для поиска местоположения нужны разрешения Android."
            lookupPhase = LookupPhase.ERROR
            lookupError = true
        }
    }

    val requestAction: (PendingAction, Boolean) -> Unit = { action, force ->
        if (action == PendingAction.REFRESH && !prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_CONSENT, false)) {
            consentForce = force
            showConsentDialog = true
        } else if (!hasLocationPermission(context)) {
            pendingAction = action
            pendingForce = force
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        } else {
            ensureRadioPermissions(action, force)
        }
    }

    DisposableEffect(permissionRevision, hasLocationPermission) {
        if (!hasLocationPermission) {
            onDispose { }
        } else {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (isMockLocation(location)) return
                    val old = liveLocation
                    if (old == null || isBetterLocation(location, old)) {
                        liveLocation = Location(location)
                    }
                }
            }

            try {
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                for (provider in providers) {
                    try {
                        val cached = manager.getLastKnownLocation(provider)
                        if (cached != null && !isMockLocation(cached) &&
                            (liveLocation == null || isBetterLocation(cached, liveLocation!!))) {
                            liveLocation = Location(cached)
                        }
                        if (manager.allProviders.contains(provider) && manager.isProviderEnabled(provider)) {
                            manager.requestLocationUpdates(provider, 2_500L, 2f, listener, Looper.getMainLooper())
                        }
                    } catch (_: SecurityException) {
                    } catch (_: IllegalArgumentException) {
                    }
                }
            } catch (_: Exception) {
            }
            onDispose {
                try { manager.removeUpdates(listener) } catch (_: SecurityException) { }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            monitoring = prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)
            mockEnabled = prefs.getBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)
            filterState = prefs.getString(LocationMonitorService.KEY_STATE, FilterState.WAITING.name) ?: FilterState.WAITING.name
            riskScore = prefs.getInt(LocationMonitorService.KEY_SCORE, 0)
            inRiskZone = prefs.getBoolean(LocationMonitorService.KEY_IN_ZONE, false)
            serviceStatus = prefs.getString(LocationMonitorService.KEY_SERVICE_STATUS, "") ?: ""
            dailyRefresh = prefs.getBoolean(ClearGpsPreferences.KEY_DAILY_REFRESH, true)
            val latest = RadioGeolocationRepository.loadLastEstimate(context)
            if (latest != radioEstimate) radioEstimate = latest
            delay(1_500L)
        }
    }

    LaunchedEffect(hasLocationPermission, permissionRevision, prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_CONSENT, false)) {
        if (hasLocationPermission &&
            prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_CONSENT, false) &&
            System.currentTimeMillis() - prefs.getLong(ClearGpsPreferences.KEY_LAST_REFRESH_AT, 0L) >= RadioGeolocationRepository.DAY_MS &&
            prefs.getString(ClearGpsPreferences.KEY_LAST_PAYLOAD, null) != null &&
            !lookupBusy) {
            runRefresh(false)
        }
    }

    val currentLocation = liveLocation?.takeIf { System.currentTimeMillis() - it.time in 0..15 * 60 * 1000L }
    val primaryCoordinate = when {
        currentLocation != null -> MapCoordinate(currentLocation.latitude, currentLocation.longitude)
        radioEstimate != null -> MapCoordinate(radioEstimate!!.latitude, radioEstimate!!.longitude)
        else -> null
    }
    val primaryAccuracy = when {
        currentLocation?.hasAccuracy() == true -> currentLocation.accuracy.toDouble()
        radioEstimate != null -> radioEstimate!!.accuracyMeters
        else -> null
    }
    val primarySource = when {
        currentLocation?.provider.equals(LocationManager.GPS_PROVIDER, ignoreCase = true) -> "GPS"
        currentLocation != null -> "Сеть Android"
        radioEstimate != null -> "BeaconDB"
        else -> "Нет фикса"
    }

    SideEffect {
        mapController.setTapMode(mapTapMode)
        mapController.onMapTap = { latitude, longitude ->
            when (mapTapMode) {
                MapTapMode.PLACE_ANCHOR -> {
                    val anchor = ManualAnchor(latitude, longitude)
                    ManualLocationStore.save(context, anchor)
                    manualAnchor = anchor
                    mapTapMode = MapTapMode.NONE
                    mapController.focus(MapCoordinate(latitude, longitude))
                    true
                }
                MapTapMode.DRAW_ZONE -> {
                    draftZone = draftZone + ZonePoint(latitude, longitude)
                    mapController.updateZone(savedZone, draftZone)
                    true
                }
                MapTapMode.NONE -> false
            }
        }
    }

    LaunchedEffect(primaryCoordinate, primaryAccuracy, manualAnchor, savedZone, draftZone) {
        mapController.updatePrimary(primaryCoordinate, primaryAccuracy)
        mapController.updateManualAnchor(manualAnchor)
        mapController.updateZone(savedZone, draftZone)
    }

    ClearGpsTheme {
        val colors = MaterialTheme.colorScheme
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().background(colors.background)
        ) {
            val panelMaxHeight = maxHeight * 0.68f
            AndroidView(
                factory = { viewContext ->
                    MapView(viewContext).also { view ->
                        onMapViewCreated(view)
                        mapController.attach(view, viewContext)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeaderCard(
                        onSettings = { showSettings = true },
                        onCenter = { requestAction(PendingAction.CENTER, false) }
                    )
                    if (mapTapMode != MapTapMode.NONE) {
                        ModeBanner(
                            text = if (mapTapMode == MapTapMode.PLACE_ANCHOR)
                                "Нажми на карту, чтобы поставить примерный ориентир"
                            else "Добавляй точки на карте, затем сохрани зону",
                            onCancel = {
                                mapTapMode = MapTapMode.NONE
                                if (draftZone.isNotEmpty()) draftZone = emptyList()
                                mapController.updateZone(savedZone, draftZone)
                            }
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxWidth().heightIn(max = panelMaxHeight),
                    shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                    color = colors.surfaceContainer,
                    tonalElevation = 4.dp,
                    shadowElevation = 12.dp
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 18.dp, vertical = 16.dp)
                            .navigationBarsPaddingCompat(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Местоположение", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (currentLocation != null) "Системная геолокация активна"
                                    else if (radioEstimate != null) "Последняя оценка по радиосетям"
                                    else "Ждём первую точку",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            StatusPill(
                                text = primarySource,
                                color = if (primaryCoordinate != null) colors.primaryContainer else colors.surfaceVariant,
                                foreground = if (primaryCoordinate != null) colors.onPrimaryContainer else colors.onSurfaceVariant
                            )
                        }

                        if (primaryCoordinate != null) {
                            Text(
                                String.format(Locale.US, "%.5f, %.5f", primaryCoordinate.latitude, primaryCoordinate.longitude),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Medium
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.GpsFixed, null, modifier = Modifier.size(16.dp), tint = colors.primary)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (primaryAccuracy != null) "Точность примерно ±" + primaryAccuracy.toInt() + " м"
                                    else "Радиус точности неизвестен",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            if (radioEstimate != null && currentLocation == null) {
                                Text(
                                    "Это оценка по базе радиосетей, а не измерение GPS. Фактическая ошибка может быть больше указанного радиуса.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        } else {
                            Text("Пока не удалось получить координаты", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Разреши геолокацию и нажми «Обновить источники». Если база не знает сети рядом, можно поставить ориентир вручную.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SourceChip(Icons.Outlined.GpsFixed, "GPS", currentLocation?.provider.equals(LocationManager.GPS_PROVIDER, true))
                            SourceChip(Icons.Outlined.Wifi, "Wi‑Fi " + wifiCount, wifiCount > 0)
                            SourceChip(Icons.Outlined.SignalCellularAlt, "Соты " + cellCount, cellCount > 0)
                            SourceChip(Icons.Outlined.Bluetooth, "BLE " + beaconCount, beaconCount > 0)
                            SourceChip(Icons.Outlined.Shield, "BeaconDB", radioEstimate != null)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            FilledTonalButton(
                                onClick = { requestAction(PendingAction.REFRESH, true) },
                                enabled = !lookupBusy,
                                modifier = Modifier.weight(1.35f).heightIn(min = 54.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                if (lookupBusy) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Сканирую…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } else {
                                    Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(19.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Обновить источники", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    mapTapMode = if (mapTapMode == MapTapMode.PLACE_ANCHOR) MapTapMode.NONE else MapTapMode.PLACE_ANCHOR
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 54.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(Icons.Outlined.EditLocationAlt, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (mapTapMode == MapTapMode.PLACE_ANCHOR) "Отмена" else "Указать точку", maxLines = 1)
                            }
                        }

                        if (lookupBusy) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = colors.primary)
                        }

                        Surface(
                            modifier = Modifier.fillMaxWidth().animateContentSize(),
                            color = if (lookupError) colors.errorContainer else colors.surfaceContainerHigh,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    imageVector = when (lookupPhase) {
                                        LookupPhase.UPDATED -> Icons.Outlined.CheckCircle
                                        LookupPhase.CACHED -> Icons.Outlined.CheckCircle
                                        LookupPhase.ERROR, LookupPhase.NO_SIGNALS -> Icons.Outlined.Info
                                        else -> Icons.Outlined.Refresh
                                    },
                                    contentDescription = null,
                                    tint = if (lookupError) colors.onErrorContainer else colors.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(
                                        when (lookupPhase) {
                                            LookupPhase.SCANNING -> "Проверяю источники"
                                            LookupPhase.UPDATED -> "Оценка обновлена"
                                            LookupPhase.CACHED -> "Используется кэш"
                                            LookupPhase.NO_SIGNALS -> "Недостаточно радиоданных"
                                            LookupPhase.ERROR -> "Не удалось обновить"
                                            LookupPhase.IDLE -> "Статус источников"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        lookupMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (lookupError) colors.onErrorContainer else colors.onSurfaceVariant
                                    )
                                    val updatedAt = prefs.getLong(ClearGpsPreferences.KEY_LAST_ESTIMATE_AT, 0L)
                                    if (updatedAt > 0L) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            "Последняя оценка: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(updatedAt)) +
                                                if (fromCache) " · кэш" else "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = colors.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        if (manualAnchor != null) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                color = colors.tertiaryContainer,
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.LocationOn, null, tint = colors.onTertiaryContainer)
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Ручной ориентир", fontWeight = FontWeight.SemiBold, color = colors.onTertiaryContainer)
                                        Text(
                                            String.format(Locale.US, "%.5f, %.5f", manualAnchor!!.latitude, manualAnchor!!.longitude),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onTertiaryContainer
                                        )
                                        Text("Показан янтарной точкой; не считается подтверждённым местоположением.",
                                            style = MaterialTheme.typography.labelSmall, color = colors.onTertiaryContainer)
                                    }
                                    IconButton(onClick = {
                                        ManualLocationStore.save(context, null)
                                        manualAnchor = null
                                    }) {
                                        Icon(Icons.Outlined.DeleteOutline, "Удалить ориентир", tint = colors.onTertiaryContainer)
                                    }
                                }
                            }
                        }

                        Divider(color = colors.outlineVariant)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Shield, null, tint = colors.primary)
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Фильтр GNSS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when (filterState) {
                                        FilterState.WAITING.name -> "Ожидание данных"
                                        FilterState.NORMAL.name -> "Аномалий не найдено"
                                        FilterState.ZONE_RISK.name -> "Внутри отмеченной зоны риска"
                                        FilterState.CORRECTING.name -> "Используется резервная позиция"
                                        FilterState.NO_FALLBACK.name -> "Нет безопасного fallback"
                                        else -> filterState
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Text(riskScore.toString() + "%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                        LinearProgressIndicator(
                            progress = { riskScore.coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = if (riskScore >= 60) colors.error else colors.primary,
                            trackColor = colors.surfaceVariant
                        )
                        Text(
                            (if (inRiskZone) "Ты внутри нарисованной зоны риска. " else "") +
                                (serviceStatus.ifBlank { if (monitoring) "Мониторинг активен." else "Фоновый мониторинг выключен." }),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    if (monitoring) runStopMonitoring()
                                    else requestAction(PendingAction.MONITOR, false)
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(if (monitoring) "Остановить мониторинг" else "Запустить мониторинг", maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = {
                                    if (mockEnabled) runDisableMock()
                                    else requestAction(PendingAction.MOCK, false)
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                shape = RoundedCornerShape(18.dp)
                            ) {
                                Text(if (mockEnabled) "Выключить коррекцию" else "Включить коррекцию", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        Divider(color = colors.outlineVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Зона риска на карте", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (savedZone.size >= 3) "Сохранено точек: " + savedZone.size
                                    else "Отметь участок, где GNSS часто ошибается",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilledTonalButton(
                                onClick = {
                                    draftZone = emptyList()
                                    mapTapMode = MapTapMode.DRAW_ZONE
                                    mapController.updateZone(savedZone, draftZone)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp)
                            ) { Text(if (mapTapMode == MapTapMode.DRAW_ZONE) "Рисуем…" else "Рисовать") }
                            OutlinedButton(
                                onClick = {
                                    if (draftZone.size >= 3) {
                                        savedZone = draftZone.toList()
                                        ZoneStore.save(context, savedZone)
                                        draftZone = emptyList()
                                        lookupMessage = "Зона риска сохранена локально."
                                    } else {
                                        lookupMessage = "Для зоны нужно минимум 3 точки на карте."
                                        lookupError = true
                                    }
                                    mapTapMode = MapTapMode.NONE
                                    mapController.updateZone(savedZone, draftZone)
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(16.dp)
                            ) { Text("Сохранить") }
                            IconButton(onClick = {
                                savedZone = emptyList()
                                draftZone = emptyList()
                                mapTapMode = MapTapMode.NONE
                                ZoneStore.save(context, emptyList())
                                mapController.updateZone(savedZone, draftZone)
                            }) {
                                Icon(Icons.Outlined.DeleteOutline, "Очистить зону")
                            }
                        }
                        if (mapTapMode == MapTapMode.DRAW_ZONE) {
                            Text("Текущих точек: " + draftZone.size, style = MaterialTheme.typography.labelMedium, color = colors.primary)
                        }

                        Text(
                            "Карта использует OpenStreetMap. BeaconDB не содержит полного скачиваемого дампа, поэтому Clear GPS кэширует оценки, а не всю базу. Точность зависит от покрытия рядом.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }

            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(
                    end = 16.dp,
                    bottom = panelMaxHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
                ),
                shape = CircleShape,
                color = colors.primaryContainer,
                shadowElevation = 6.dp
            ) {
                IconButton(onClick = { requestAction(PendingAction.CENTER, false) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Outlined.MyLocation, "Центрировать карту", tint = colors.onPrimaryContainer)
                }
            }

            MapAttribution(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = panelMaxHeight + 18.dp),
                onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) }
                    catch (_: Exception) { }
                }
            )
        }

        if (showConsentDialog) {
            AlertDialog(
                onDismissRequest = { showConsentDialog = false },
                icon = { Icon(Icons.Outlined.Info, null) },
                title = { Text("Разрешить поиск по радиосетям?") },
                text = {
                    Text(
                        "Clear GPS просканирует доступные Wi‑Fi-точки, мобильные соты и Bluetooth-маяки и отправит их идентификаторы и уровень сигнала в BeaconDB по HTTPS, чтобы получить примерные координаты. SSID, IMEI, контакты и GPS-координаты не отправляются. Радиоснимок сохраняется в приватных данных приложения для обновления примерно раз в сутки. Покрытие базы неполное, результат может ошибаться."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit()
                            .putBoolean(ClearGpsPreferences.KEY_RADIO_CONSENT, true)
                            .putBoolean(ClearGpsPreferences.KEY_DAILY_REFRESH, true)
                            .apply()
                        showConsentDialog = false
                        dailyRefresh = true
                        requestAction(PendingAction.REFRESH, consentForce)
                    }) { Text("Продолжить") }
                },
                dismissButton = {
                    TextButton(onClick = { showConsentDialog = false }) { Text("Отмена") }
                }
            )
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                icon = { Icon(Icons.Outlined.Settings, null) },
                title = { Text("Настройки и приватность") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Автообновление базы", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = dailyRefresh,
                                onCheckedChange = { checked ->
                                    dailyRefresh = checked
                                    prefs.edit().putBoolean(ClearGpsPreferences.KEY_DAILY_REFRESH, checked).apply()
                                    if (checked && prefs.getString(ClearGpsPreferences.KEY_LAST_PAYLOAD, null) != null &&
                                        prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_CONSENT, false)) {
                                        RadioGeolocationRepository.scheduleDailyRefresh(context)
                                    } else if (!checked) {
                                        RadioGeolocationRepository.cancelDailyRefresh(context)
                                    }
                                }
                            )
                            Text("Обновлять сохранённый радиоснимок в фоне примерно раз в 24 часа", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "Фоновое обновление повторно проверяет последний сохранённый набор сетей. Оно не запускает ежедневное скрытое сканирование радиоэфира. Android может сдвигать время запуска из-за энергосбережения.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                showSettings = false
                                showClearCacheDialog = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Удалить кэш и радиоданные")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showSettings = false }) { Text("Готово") } }
            )
        }

        if (showClearCacheDialog) {
            AlertDialog(
                onDismissRequest = { showClearCacheDialog = false },
                title = { Text("Очистить сохранённые данные?") },
                text = { Text("Будут удалены радиоснимок, локальная оценка и согласие на отправку. Ручной ориентир и зона риска останутся на устройстве.") },
                confirmButton = {
                    TextButton(onClick = {
                        RadioGeolocationRepository.cancelDailyRefresh(context)
                        prefs.edit()
                            .remove(ClearGpsPreferences.KEY_LAST_PAYLOAD)
                            .remove(ClearGpsPreferences.KEY_LAST_FINGERPRINT)
                            .remove(ClearGpsPreferences.KEY_LAST_REFRESH_AT)
                            .remove(ClearGpsPreferences.KEY_LAST_LAT)
                            .remove(ClearGpsPreferences.KEY_LAST_LON)
                            .remove(ClearGpsPreferences.KEY_LAST_ACCURACY)
                            .remove(ClearGpsPreferences.KEY_LAST_ESTIMATE_AT)
                            .remove(ClearGpsPreferences.KEY_LAST_FALLBACK)
                            .putBoolean(ClearGpsPreferences.KEY_RADIO_CONSENT, false)
                            .apply()
                        radioEstimate = null
                        wifiCount = 0; cellCount = 0; beaconCount = 0
                        lookupMessage = "Кэш удалён. При следующем запросе снова появится объяснение передачи радиоданных."
                        lookupPhase = LookupPhase.IDLE
                        lookupError = false
                        showClearCacheDialog = false
                    }) { Text("Удалить") }
                },
                dismissButton = { TextButton(onClick = { showClearCacheDialog = false }) { Text("Оставить") } }
            )
        }
    }
}

@Composable
private fun ClearGpsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        darkColorScheme(
            primary = Color(0xFF9BBEFF),
            secondary = Color(0xFFBFC7DC),
            tertiary = Color(0xFFE7B9FA),
            surface = Color(0xFF121722),
            background = Color(0xFF0E121B)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF285DB4),
            secondary = Color(0xFF515F79),
            tertiary = Color(0xFF725580),
            surface = Color(0xFFF7F8FC),
            background = Color(0xFFF1F3F8)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun HeaderCard(onSettings: () -> Unit, onCenter: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        color = colors.surface.copy(alpha = 0.94f),
        tonalElevation = 4.dp,
        shadowElevation = 5.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 7.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(17.dp), color = colors.primaryContainer, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.GpsFixed, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Clear GPS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("GNSS · радиоисточники · зоны риска", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = onCenter) {
                Icon(Icons.Outlined.MyLocation, "Моё местоположение")
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, "Настройки")
            }
        }
    }
}

@Composable
private fun ModeBanner(text: String, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.tertiaryContainer,
        shape = RoundedCornerShape(19.dp),
        shadowElevation = 3.dp
    ) {
        Row(modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.EditLocationAlt, null, tint = colors.onTertiaryContainer)
            Spacer(Modifier.width(10.dp))
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onTertiaryContainer)
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.DeleteOutline, "Отменить режим", tint = colors.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color, foreground: Color) {
    Surface(shape = CircleShape, color = color) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium, color = foreground, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SourceChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    AssistChip(
        onClick = { },
        label = { Text(text, maxLines = 1) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(17.dp)) },
        enabled = false,
        shape = RoundedCornerShape(15.dp),
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (active) colors.secondaryContainer else colors.surfaceVariant,
            disabledLabelColor = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
            disabledLeadingIconContentColor = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant
        )
    )
}

@Composable
private fun MapAttribution(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = Color.White.copy(alpha = 0.90f),
        shadowElevation = 2.dp,
        onClick = onClick
    ) {
        Text("© OpenStreetMap", modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            color = Color(0xFF253044), style = MaterialTheme.typography.labelSmall)
    }
}

private fun hasLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun isMockLocation(location: Location): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider

private fun isBetterLocation(candidate: Location, previous: Location): Boolean {
    val now = System.currentTimeMillis()
    val candidateAge = (now - candidate.time).coerceAtLeast(0L)
    val previousAge = (now - previous.time).coerceAtLeast(0L)
    if (candidateAge < 30_000L && previousAge > 120_000L) return true
    if (candidate.provider.equals(LocationManager.GPS_PROVIDER, true) &&
        !previous.provider.equals(LocationManager.GPS_PROVIDER, true) &&
        candidateAge < 120_000L) return true
    if (previous.provider.equals(LocationManager.GPS_PROVIDER, true) &&
        candidate.provider.equals(LocationManager.NETWORK_PROVIDER, true) &&
        previousAge < 120_000L) return false
    val candidateAccuracy = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
    val previousAccuracy = if (previous.hasAccuracy()) previous.accuracy else Float.MAX_VALUE
    return candidateAge < 120_000L && (candidateAccuracy < previousAccuracy || candidateAge < previousAge)
}

private fun Modifier.navigationBarsPaddingCompat(): Modifier =
    this.padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())
