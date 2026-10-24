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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cleargps.core.FilterState
import org.cleargps.core.SavedRiskZone
import org.cleargps.core.ZonePoint
import org.cleargps.core.ZoneStore
import org.maplibre.android.maps.MapView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class AppTab { MAP, POINTS, ZONES, SETTINGS }
private enum class PointsView { POINTS, HISTORY }
private enum class LookupPhase { IDLE, SCANNING, UPDATED, NO_MATCH, ERROR }
private enum class PositionMode(val key: String, val title: String) {
    AUTO("auto", "Авто"), GPS_ONLY("gps", "GPS"), RADIO_ONLY("radio", "Только радио")
}
private enum class MapThemeMode(val key: String, val title: String) {
    SYSTEM("system", "Система"), LIGHT("light", "Светлая"), DARK("dark", "Тёмная")
}

@Composable
fun ClearGpsApp(onMapViewCreated: (MapView) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE) }
    val repository = remember { RadioGeolocationRepository(context.applicationContext) }
    val database = remember { LocalRadioDatabase(context.applicationContext) }
    val mapController = remember { ClearGpsMapController() }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()

    var appVisible by remember { mutableStateOf(true) }
    var positionMode by remember {
        mutableStateOf(PositionMode.values().firstOrNull { it.key == prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") } ?: PositionMode.AUTO)
    }
    var mapThemeMode by remember {
        mutableStateOf(MapThemeMode.values().firstOrNull { it.key == prefs.getString(ClearGpsPreferences.KEY_MAP_STYLE, "system") } ?: MapThemeMode.SYSTEM)
    }
    val mapIsDark = when (mapThemeMode) {
        MapThemeMode.SYSTEM -> systemDark
        MapThemeMode.LIGHT -> false
        MapThemeMode.DARK -> true
    }

    var liveLocation by remember { mutableStateOf<Location?>(null) }
    var currentRadioEstimate by remember { mutableStateOf<RadioEstimate?>(null) }
    var manualAnchor by remember { mutableStateOf(ManualLocationStore.load(context)) }
    var manualFallbackEnabled by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_MANUAL_FALLBACK_ENABLED, false)) }
    var zones by remember { mutableStateOf(ZoneStore.loadAll(context)) }
    var draftZone by remember { mutableStateOf(emptyList<ZonePoint>()) }
    var mapTapMode by remember { mutableStateOf(MapTapMode.NONE) }
    var selectedTab by remember { mutableStateOf(AppTab.MAP) }
    var pointsView by remember { mutableStateOf(PointsView.POINTS) }
    var points by remember { mutableStateOf(database.listPoints(500)) }
    var history by remember { mutableStateOf(database.listHistory(150)) }
    var pointCount by remember { mutableStateOf(database.pointCount()) }
    var observationCount by remember { mutableStateOf(database.observationCount()) }
    var showRadioPointsOnMap by remember { mutableStateOf(true) }

    var useWifi by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_USE_WIFI, true)) }
    var useCells by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_USE_CELLS, true)) }
    var useBle by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_USE_BLE, true)) }
    var recording by remember { mutableStateOf(false) }
    var scanBusy by remember { mutableStateOf(false) }
    var scanMessage by remember {
        mutableStateOf("Сканирование локальное. Запиши радиоточки на известном месте, чтобы затем ориентироваться по ним без GPS.")
    }
    var lookupPhase by remember { mutableStateOf(LookupPhase.IDLE) }
    var wifiCount by remember { mutableStateOf(prefs.getInt(ClearGpsPreferences.KEY_LAST_WIFI_COUNT, 0)) }
    var cellCount by remember { mutableStateOf(prefs.getInt(ClearGpsPreferences.KEY_LAST_CELL_COUNT, 0)) }
    var bleCount by remember { mutableStateOf(prefs.getInt(ClearGpsPreferences.KEY_LAST_BLE_COUNT, 0)) }
    var lastScanAt by remember { mutableStateOf(prefs.getLong(ClearGpsPreferences.KEY_LAST_SCAN_AT, 0L)) }
    var recordedCount by remember { mutableStateOf(prefs.getInt(ClearGpsPreferences.KEY_LAST_RECORDED_COUNT, 0)) }
    var monitorEnabled by remember { mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)) }
    var mockEnabled by remember { mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)) }
    var filterState by remember { mutableStateOf(prefs.getString(LocationMonitorService.KEY_STATE, FilterState.WAITING.name) ?: FilterState.WAITING.name) }
    var riskScore by remember { mutableStateOf(prefs.getInt(LocationMonitorService.KEY_SCORE, 0)) }
    var serviceStatus by remember { mutableStateOf(prefs.getString(LocationMonitorService.KEY_SERVICE_STATUS, "") ?: "") }

    var pointToEdit by remember { mutableStateOf<RadioPointRecord?>(null) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showZoneNameDialog by remember { mutableStateOf(false) }
    var zoneName by remember { mutableStateOf("") }
    var zoneToRename by remember { mutableStateOf<SavedRiskZone?>(null) }
    var zoneRenameText by remember { mutableStateOf("") }
    var showRadioOnlyInfo by remember { mutableStateOf(false) }
    var pendingRecordScan by remember { mutableStateOf(false) }

    fun refreshLocalUi() {
        points = database.listPoints(500)
        history = database.listHistory(150)
        pointCount = database.pointCount()
        observationCount = database.observationCount()
        zones = ZoneStore.loadAll(context)
    }

    fun saveUseFlags() {
        prefs.edit().putBoolean(ClearGpsPreferences.KEY_USE_WIFI, useWifi)
            .putBoolean(ClearGpsPreferences.KEY_USE_CELLS, useCells)
            .putBoolean(ClearGpsPreferences.KEY_USE_BLE, useBle).apply()
    }

    val performScan: suspend (Boolean) -> Unit = { recordNow ->
        if (!scanBusy) {
            scanBusy = true
            lookupPhase = LookupPhase.SCANNING
            scanMessage = if (recordNow) "Сохраняю наблюдения в локальную SQLite-базу…" else "Сканирую Wi‑Fi, мобильные соты и Bluetooth LE…"
            try {
                val reference = currentReference(
                    if (positionMode == PositionMode.RADIO_ONLY) null else liveLocation,
                    manualAnchor
                )
                val result = repository.scan(recordNow, reference, positionMode.key, useWifi, useCells, useBle)
                wifiCount = result.wifiCount
                cellCount = result.cellCount
                bleCount = result.beaconCount
                recordedCount = result.recordedCount
                lastScanAt = System.currentTimeMillis()
                currentRadioEstimate = result.estimate
                scanMessage = result.message +
                    if (recordNow && result.recordedCount > 0) " Сохранено/обновлено записей: " + result.recordedCount + "." else ""
                lookupPhase = when {
                    result.estimate != null -> LookupPhase.UPDATED
                    result.wifiCount + result.cellCount + result.beaconCount == 0 -> LookupPhase.ERROR
                    else -> LookupPhase.NO_MATCH
                }
                withContext(Dispatchers.IO) { refreshLocalUi() }
            } catch (_: SecurityException) {
                scanMessage = "Android запретил сканирование. Проверь разрешения и системные настройки геолокации."
                lookupPhase = LookupPhase.ERROR
            } catch (error: Exception) {
                scanMessage = "Ошибка локального сканирования: " + (error.message ?: "неизвестная ошибка")
                lookupPhase = LookupPhase.ERROR
            } finally {
                scanBusy = false
            }
        }
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.BLUETOOTH_SCAN] == true || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            scope.launch { performScan(pendingRecordScan) }
        } else {
            scanMessage = "Bluetooth-разрешение не выдано. Wi‑Fi и сотовые источники можно продолжить использовать."
            lookupPhase = LookupPhase.ERROR
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) {
            if (useBle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN))
            } else {
                scope.launch { performScan(pendingRecordScan) }
            }
        } else {
            scanMessage = "Без разрешения геолокации Android ограничивает сканирование Wi‑Fi и сотовых сетей."
            lookupPhase = LookupPhase.ERROR
        }
    }

    val startScan: (Boolean) -> Unit = { recordNow ->
        pendingRecordScan = recordNow
        if (!hasAnyLocationPermission(context)) {
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else if (useBle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN))
        } else {
            scope.launch { performScan(recordNow) }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val csv = withContext(Dispatchers.IO) { database.exportWigleCsv() }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                        ?: throw IllegalStateException("Не удалось открыть файл для записи.")
                }
                Toast.makeText(context, "WiGLE CSV сохранён. Автоматической отправки нет.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, "Ошибка экспорта: " + (error.message ?: "неизвестная ошибка"), Toast.LENGTH_LONG).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val imported = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Не удалось открыть файл.")
                    database.importWigleCsv(input)
                }
                refreshLocalUi()
                Toast.makeText(context, "Импортировано WiGLE-точек: " + imported, Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, "Ошибка импорта: " + (error.message ?: "неизвестная ошибка"), Toast.LENGTH_LONG).show()
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appVisible = true
                Lifecycle.Event.ON_STOP -> appVisible = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(positionMode, hasAnyLocationPermission(context)) {
        if (positionMode == PositionMode.RADIO_ONLY) liveLocation = null
    }

    DisposableEffect(positionMode, hasAnyLocationPermission(context)) {
        if (!hasAnyLocationPermission(context) || positionMode == PositionMode.RADIO_ONLY) {
            onDispose { }
        } else {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (isMockLocation(location)) return
                    if (positionMode == PositionMode.GPS_ONLY && !location.provider.equals(LocationManager.GPS_PROVIDER, true)) return
                    val old = liveLocation
                    if (old == null || isBetterLocation(location, old)) liveLocation = Location(location)
                }
            }
            try {
                val providers = if (positionMode == PositionMode.GPS_ONLY) listOf(LocationManager.GPS_PROVIDER)
                    else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                providers.forEach { provider ->
                    try {
                        val cached = manager.getLastKnownLocation(provider)
                        if (cached != null && !isMockLocation(cached) &&
                            (positionMode != PositionMode.GPS_ONLY || provider == LocationManager.GPS_PROVIDER) &&
                            (liveLocation == null || isBetterLocation(cached, liveLocation!!))) liveLocation = Location(cached)
                        if (manager.allProviders.contains(provider) && manager.isProviderEnabled(provider)) {
                            manager.requestLocationUpdates(provider, 2_500L, 2f, listener, Looper.getMainLooper())
                        }
                    } catch (_: SecurityException) {
                    } catch (_: IllegalArgumentException) {
                    }
                }
            } catch (_: Exception) {
            }
            onDispose { try { manager.removeUpdates(listener) } catch (_: SecurityException) { } }
        }
    }

    LaunchedEffect(recording, appVisible, hasAnyLocationPermission(context), positionMode) {
        if (recording && appVisible && hasAnyLocationPermission(context)) {
            while (recording && appVisible) {
                performScan(true)
                delay(RECORD_INTERVAL_MS)
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            monitorEnabled = prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)
            mockEnabled = prefs.getBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)
            filterState = prefs.getString(LocationMonitorService.KEY_STATE, FilterState.WAITING.name) ?: FilterState.WAITING.name
            riskScore = prefs.getInt(LocationMonitorService.KEY_SCORE, 0)
            serviceStatus = prefs.getString(LocationMonitorService.KEY_SERVICE_STATUS, "") ?: ""
            delay(1_500L)
        }
    }

    val validLive = liveLocation?.takeIf { System.currentTimeMillis() - it.time in 0..15 * 60 * 1000L }
    val activeLocation = when (positionMode) {
        PositionMode.GPS_ONLY -> validLive?.takeIf { it.provider.equals(LocationManager.GPS_PROVIDER, true) }
        PositionMode.AUTO -> validLive
        PositionMode.RADIO_ONLY -> null
    }
    val measuredCoordinate = activeLocation?.let { MapCoordinate(it.latitude, it.longitude) }
    val radioCoordinate = currentRadioEstimate?.let { MapCoordinate(it.latitude, it.longitude) }
    val primaryCoordinate = when (positionMode) {
        PositionMode.GPS_ONLY -> measuredCoordinate
        PositionMode.AUTO -> measuredCoordinate ?: radioCoordinate
        PositionMode.RADIO_ONLY -> radioCoordinate
    }
    val primaryAccuracy = when {
        activeLocation?.hasAccuracy() == true -> activeLocation.accuracy.toDouble()
        radioCoordinate != null -> currentRadioEstimate?.accuracyMeters
        else -> null
    }
    val displayCoordinate = primaryCoordinate ?: manualAnchor?.let { MapCoordinate(it.latitude, it.longitude) }
    val primarySource = when {
        positionMode == PositionMode.RADIO_ONLY && currentRadioEstimate != null -> "Локальное радио"
        activeLocation?.provider.equals(LocationManager.GPS_PROVIDER, true) -> "GPS"
        positionMode == PositionMode.GPS_ONLY && activeLocation == null -> "Ожидание GPS"
        activeLocation != null -> "Сеть Android"
        currentRadioEstimate != null -> "Локальная база"
        manualAnchor != null -> "Ориентир"
        else -> "Нет фикса"
    }

    SideEffect {
        mapController.setDarkMode(mapIsDark)
        mapController.setShowRadioPoints(showRadioPointsOnMap)
        mapController.updatePrimary(primaryCoordinate, primaryAccuracy)
        mapController.updateManualAnchor(manualAnchor)
        mapController.updateZones(zones, draftZone)
        mapController.updateRadioPoints(points)
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
                    mapController.updateZones(zones, draftZone)
                    true
                }
                MapTapMode.NONE -> false
            }
        }
    }

    val runMonitoring: () -> Unit = {
        val intent = Intent(context, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        prefs.edit().putBoolean(LocationMonitorService.KEY_MONITORING, true).apply()
        monitorEnabled = true
        serviceStatus = "Мониторинг запущен"
    }
    val stopMonitoring: () -> Unit = {
        context.startService(Intent(context, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_STOP))
        prefs.edit().putBoolean(LocationMonitorService.KEY_MONITORING, false).apply()
        monitorEnabled = false
        serviceStatus = "Мониторинг остановлен"
    }
    val enableMock: () -> Unit = {
        val intent = Intent(context, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        prefs.edit().putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, true).apply()
        mockEnabled = true
        Toast.makeText(context, "Выбери Clear GPS как приложение фиктивного местоположения в настройках разработчика.", Toast.LENGTH_LONG).show()
    }
    val disableMock: () -> Unit = {
        context.startService(Intent(context, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_DISABLE_MOCK))
        prefs.edit().putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false).apply()
        mockEnabled = false
    }

    ClearGpsTheme {
        val colors = MaterialTheme.colorScheme
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
            val panelMaxHeight = maxHeight * 0.70f
            AndroidView(factory = { viewContext ->
                MapView(viewContext).also { view ->
                    onMapViewCreated(view)
                    mapController.attach(view, viewContext, mapIsDark)
                }
            }, modifier = Modifier.fillMaxSize())

            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 7.dp)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeaderCard(tab = selectedTab, onCenter = { mapController.focusPrimary() }, onSettings = { selectedTab = AppTab.SETTINGS })
                    if (mapTapMode != MapTapMode.NONE) {
                        ModeBanner(
                            text = if (mapTapMode == MapTapMode.PLACE_ANCHOR) "Нажми карту, чтобы поставить ориентир"
                                else "Нажимай карту, чтобы добавить вершины зоны",
                            onCancel = {
                                mapTapMode = MapTapMode.NONE
                                if (draftZone.isNotEmpty()) draftZone = emptyList()
                                mapController.updateZones(zones, draftZone)
                            }
                        )
                    }
                }

                Surface(
                    Modifier.fillMaxWidth().heightIn(max = panelMaxHeight),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = colors.surfaceContainer, tonalElevation = 4.dp, shadowElevation = 12.dp
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        Box(Modifier.fillMaxWidth().heightIn(max = panelMaxHeight - 74.dp)) {
                            when (selectedTab) {
                                AppTab.MAP -> MapHomeScreen(
                                    coordinate = displayCoordinate,
                                    accuracy = if (primaryCoordinate != null) primaryAccuracy else null,
                                    source = primarySource, mode = positionMode, currentEstimate = currentRadioEstimate,
                                    manualAnchor = manualAnchor, wifiCount = wifiCount, cellCount = cellCount, bleCount = bleCount,
                                    pointCount = pointCount, recording = recording, scanBusy = scanBusy, lookupPhase = lookupPhase,
                                    scanMessage = scanMessage, lastScanAt = lastScanAt, monitorEnabled = monitorEnabled,
                                    mockEnabled = mockEnabled, filterState = filterState, riskScore = riskScore, serviceStatus = serviceStatus,
                                    onMode = {
                                        positionMode = it
                                        prefs.edit().putString(ClearGpsPreferences.KEY_POSITION_MODE, it.key).apply()
                                        currentRadioEstimate = null
                                        if (it == PositionMode.RADIO_ONLY) showRadioOnlyInfo = true
                                    },
                                    onScan = { startScan(recording) },
                                    onManualAnchor = { mapTapMode = if (mapTapMode == MapTapMode.PLACE_ANCHOR) MapTapMode.NONE else MapTapMode.PLACE_ANCHOR },
                                    onRecording = { recording = it },
                                    onMonitoring = { if (monitorEnabled) stopMonitoring() else runMonitoring() },
                                    onMock = { if (mockEnabled) disableMock() else enableMock() },
                                    onOpenPoints = { selectedTab = AppTab.POINTS },
                                    onOpenZones = { selectedTab = AppTab.ZONES }
                                )
                                AppTab.POINTS -> DatabaseScreen(
                                    points = points, history = history, pointCount = pointCount, observationCount = observationCount,
                                    selectedView = pointsView, onView = { pointsView = it }, onEdit = { pointToEdit = it },
                                    onDelete = {
                                        database.deletePoint(it.id); refreshLocalUi()
                                        Toast.makeText(context, "Точка удалена локально", Toast.LENGTH_SHORT).show()
                                    },
                                    onImport = { importLauncher.launch(arrayOf("text/*", "application/gzip", "application/octet-stream")) },
                                    onExport = { exportLauncher.launch("ClearGPS-WiGLE.csv") },
                                    onClear = { showDeleteAllDialog = true },
                                    onRefresh = { refreshLocalUi() }
                                )
                                AppTab.ZONES -> ZonesScreen(
                                    zones = zones, draftZone = draftZone, mapTapMode = mapTapMode,
                                    onStartDraw = {
                                        draftZone = emptyList(); mapTapMode = MapTapMode.DRAW_ZONE
                                        mapController.updateZones(zones, draftZone)
                                    },
                                    onSaveDraft = {
                                        if (draftZone.size >= 3) {
                                            zoneName = "Зона спуфинга " + (zones.size + 1); showZoneNameDialog = true
                                        } else Toast.makeText(context, "Нужно минимум 3 точки на карте.", Toast.LENGTH_SHORT).show()
                                    },
                                    onToggle = { zone, enabled ->
                                        zones = zones.map { if (it.id == zone.id) it.copy(enabled = enabled) else it }
                                        ZoneStore.saveAll(context, zones)
                                    },
                                    onRename = { zone -> zoneToRename = zone; zoneRenameText = zone.name },
                                    onDelete = { zone ->
                                        zones = zones.filterNot { it.id == zone.id }; ZoneStore.saveAll(context, zones)
                                        mapController.updateZones(zones, draftZone)
                                    },
                                    onCancelDraw = {
                                        mapTapMode = MapTapMode.NONE; draftZone = emptyList()
                                        mapController.updateZones(zones, draftZone)
                                    }
                                )
                                AppTab.SETTINGS -> SettingsScreen(
                                    mode = positionMode, mapTheme = mapThemeMode, useWifi = useWifi, useCells = useCells,
                                    useBle = useBle, showRadioPoints = showRadioPointsOnMap,
                                    databaseFacts = "Точек: " + pointCount + " · наблюдений: " + observationCount,
                                    onMode = {
                                        positionMode = it
                                        prefs.edit().putString(ClearGpsPreferences.KEY_POSITION_MODE, it.key).apply()
                                        currentRadioEstimate = null
                                        if (it == PositionMode.RADIO_ONLY) showRadioOnlyInfo = true
                                    },
                                    onMapTheme = {
                                        mapThemeMode = it
                                        prefs.edit().putString(ClearGpsPreferences.KEY_MAP_STYLE, it.key).apply()
                                    },
                                    onWifi = { useWifi = it; saveUseFlags() },
                                    onCells = { useCells = it; saveUseFlags() },
                                    onBle = { useBle = it; saveUseFlags() },
                                    onShowPoints = { showRadioPointsOnMap = it },
                                    onManualAnchor = { mapTapMode = MapTapMode.PLACE_ANCHOR },
                                    onClearData = { showDeleteAllDialog = true },
                                    onOpenLocationSettings = {
                                        try { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) { }
                                    }
                                )
                            }
                        }

                        NavigationBar(windowInsets = WindowInsets.navigationBars, tonalElevation = 2.dp) {
                            NavigationBarItem(selected = selectedTab == AppTab.MAP, onClick = { selectedTab = AppTab.MAP },
                                icon = { Icon(Icons.Outlined.Map, null) }, label = { Text("Карта", maxLines = 1) })
                            NavigationBarItem(selected = selectedTab == AppTab.POINTS, onClick = { selectedTab = AppTab.POINTS; refreshLocalUi() },
                                icon = { Icon(Icons.Outlined.Storage, null) }, label = { Text("База", maxLines = 1) })
                            NavigationBarItem(selected = selectedTab == AppTab.ZONES, onClick = { selectedTab = AppTab.ZONES },
                                icon = { Icon(Icons.Outlined.Shield, null) }, label = { Text("Зоны", maxLines = 1) })
                            NavigationBarItem(selected = selectedTab == AppTab.SETTINGS, onClick = { selectedTab = AppTab.SETTINGS },
                                icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text("Настройки", maxLines = 1) })
                        }
                    }
                }
            }

            Surface(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp,
                    bottom = panelMaxHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp),
                shape = CircleShape, color = colors.primaryContainer, shadowElevation = 6.dp
            ) {
                IconButton(onClick = { mapController.focusPrimary() }, modifier = Modifier.size(50.dp)) {
                    Icon(Icons.Outlined.MyLocation, "Центрировать карту", tint = colors.onPrimaryContainer)
                }
            }
            MapAttribution(
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = panelMaxHeight + 14.dp),
                dark = mapIsDark,
                onClick = {
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) } catch (_: Exception) { }
                }
            )
        }
    }

    if (pointToEdit != null) {
        PointEditDialog(pointToEdit!!, onDismiss = { pointToEdit = null }, onSave = { label, notes, lat, lon, accuracy ->
            val saved = database.updatePoint(pointToEdit!!.id, label, notes, lat, lon, accuracy)
            pointToEdit = null; refreshLocalUi()
            Toast.makeText(context, if (saved) "Точка сохранена локально" else "Проверь координаты", Toast.LENGTH_SHORT).show()
        })
    }

    if (showZoneNameDialog) {
        AlertDialog(
            onDismissRequest = { showZoneNameDialog = false }, title = { Text("Сохранить зону спуфинга") },
            text = { OutlinedTextField(value = zoneName, onValueChange = { zoneName = it.take(80) }, label = { Text("Название зоны") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (draftZone.size >= 3) {
                        zones = zones + SavedRiskZone(name = zoneName.trim().ifBlank { "Зона спуфинга" }, points = draftZone.toList())
                        ZoneStore.saveAll(context, zones); draftZone = emptyList(); mapTapMode = MapTapMode.NONE
                        mapController.updateZones(zones, draftZone); showZoneNameDialog = false
                        Toast.makeText(context, "Зона сохранена на устройстве", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showZoneNameDialog = false }) { Text("Отмена") } }
        )
    }

    if (zoneToRename != null) {
        AlertDialog(
            onDismissRequest = { zoneToRename = null }, title = { Text("Переименовать зону") },
            text = { OutlinedTextField(value = zoneRenameText, onValueChange = { zoneRenameText = it.take(80) }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val selected = zoneToRename!!
                    zones = zones.map { if (it.id == selected.id) it.copy(name = zoneRenameText.trim().ifBlank { "Зона спуфинга" }) else it }
                    ZoneStore.saveAll(context, zones); zoneToRename = null
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { zoneToRename = null }) { Text("Отмена") } }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false }, title = { Text("Удалить локальную базу?") },
            text = { Text("Будут удалены все сохранённые радио-точки и история сканирований. Зоны риска и ручной ориентир останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    database.clearAll(); currentRadioEstimate = null
                    prefs.edit().remove(ClearGpsPreferences.KEY_LAST_LAT).remove(ClearGpsPreferences.KEY_LAST_LON)
                        .remove(ClearGpsPreferences.KEY_LAST_ACCURACY).remove(ClearGpsPreferences.KEY_LAST_ESTIMATE_AT)
                        .remove(ClearGpsPreferences.KEY_LAST_FINGERPRINT).remove(ClearGpsPreferences.KEY_LAST_MATCHED_COUNT)
                        .remove(ClearGpsPreferences.KEY_LAST_WIFI_COUNT).remove(ClearGpsPreferences.KEY_LAST_CELL_COUNT)
                        .remove(ClearGpsPreferences.KEY_LAST_BLE_COUNT).remove(ClearGpsPreferences.KEY_LAST_SCAN_AT)
                        .remove(ClearGpsPreferences.KEY_LAST_RECORDED_COUNT).apply()
                    refreshLocalUi(); scanMessage = "Локальная база и история удалены. Данные никуда не отправлялись."
                    showDeleteAllDialog = false
                }) { Text("Удалить всё") }
            },
            dismissButton = { TextButton(onClick = { showDeleteAllDialog = false }) { Text("Отмена") } }
        )
    }

    if (showRadioOnlyInfo) {
        AlertDialog(
            onDismissRequest = { showRadioOnlyInfo = false }, icon = { Icon(Icons.Outlined.Info, null) },
            title = { Text("Режим «Только радио»") },
            text = {
                Text("Clear GPS не будет запрашивать GPS-провайдер и будет определять положение только по совпадениям с точками из локальной базы. Сначала собери базу в местах с известными координатами или импортируй WiGLE CSV. Без совпадений координат не будет. Обычное приложение Android не может физически выключить GPS-модуль для всей системы; для этого используй системные настройки. Android также может ограничивать Wi‑Fi/Bluetooth-сканирование, когда глобальная геолокация выключена.")
            },
            confirmButton = { TextButton(onClick = { showRadioOnlyInfo = false }) { Text("Понятно") } }
        )
    }
}

@Composable
private fun MapHomeScreen(
    coordinate: MapCoordinate?, accuracy: Double?, source: String, mode: PositionMode,
    currentEstimate: RadioEstimate?, manualAnchor: ManualAnchor?, wifiCount: Int, cellCount: Int, bleCount: Int,
    pointCount: Int, recording: Boolean, scanBusy: Boolean, lookupPhase: LookupPhase, scanMessage: String,
    lastScanAt: Long, monitorEnabled: Boolean, mockEnabled: Boolean, filterState: String, riskScore: Int, serviceStatus: String,
    onMode: (PositionMode) -> Unit, onScan: () -> Unit, onManualAnchor: () -> Unit,
    onRecording: (Boolean) -> Unit, onMonitoring: () -> Unit, onMock: () -> Unit, onOpenPoints: () -> Unit, onOpenZones: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Текущее положение", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(source, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            StatusPill(if (coordinate != null) "Позиция" else "Нет фикса",
                if (coordinate != null) colors.primaryContainer else colors.surfaceVariant,
                if (coordinate != null) colors.onPrimaryContainer else colors.onSurfaceVariant)
        }
        if (coordinate != null) {
            Text(String.format(Locale.US, "%.5f, %.5f", coordinate.latitude, coordinate.longitude),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
            Text(if (accuracy != null) "Оценка: примерно ±" + accuracy.toInt() + " м" else "Точность неизвестна",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (mode == PositionMode.RADIO_ONLY && currentEstimate != null) {
                Text("Совпало локальных радио-точек: " + currentEstimate.matchedCount +
                    ". Радиус рассчитан эвристически и не гарантирует такую же фактическую ошибку.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        } else {
            Text(when (mode) {
                PositionMode.RADIO_ONLY -> "Нет совпадений в локальной базе"
                PositionMode.GPS_ONLY -> "Ожидание GPS-фикса"
                PositionMode.AUTO -> "Пока нет координат"
            }, style = MaterialTheme.typography.titleMedium)
            Text(if (mode == PositionMode.RADIO_ONLY)
                "Сначала запиши точки при известном положении или импортируй локальный WiGLE CSV."
            else "Разреши геолокацию и нажми «Сканировать». Для радиопозиционирования нужно предварительно записать точки.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Text("Источник координат", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            PositionMode.values().forEach { item ->
                AssistChip(onClick = { onMode(item) }, label = { Text(item.title) },
                    leadingIcon = { if (mode == item) Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(16.dp)) })
            }
        }
        if (mode == PositionMode.RADIO_ONLY) {
            Text("Этот режим игнорирует GPS внутри Clear GPS. Он не выключает GNSS для других приложений.",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = onScan, enabled = !scanBusy,
                modifier = Modifier.weight(1.2f).heightIn(min = 50.dp), shape = RoundedCornerShape(18.dp)) {
                if (scanBusy) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp)); Text("Сканирую…")
                } else {
                    Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(7.dp)); Text("Сканировать")
                }
            }
            OutlinedButton(onClick = onManualAnchor, modifier = Modifier.weight(1f).heightIn(min = 50.dp),
                shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Outlined.EditLocationAlt, null); Spacer(Modifier.width(5.dp)); Text("Ориентир")
            }
        }
        if (scanBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Surface(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(18.dp),
            color = if (lookupPhase == LookupPhase.ERROR) colors.errorContainer else colors.surfaceContainerHigh) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (lookupPhase) {
                        LookupPhase.UPDATED -> Icons.Outlined.CheckCircle
                        LookupPhase.ERROR -> Icons.Outlined.Info
                        else -> Icons.Outlined.Refresh
                    }, null, tint = if (lookupPhase == LookupPhase.ERROR) colors.onErrorContainer else colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(when (lookupPhase) {
                        LookupPhase.SCANNING -> "Сканирование активно"
                        LookupPhase.UPDATED -> "Локальная позиция рассчитана"
                        LookupPhase.NO_MATCH -> "Сигналы есть, координат пока нет"
                        LookupPhase.ERROR -> "Сканирование требует внимания"
                        LookupPhase.IDLE -> "Локальные источники"
                    }, fontWeight = FontWeight.SemiBold)
                }
                Text(scanMessage, style = MaterialTheme.typography.bodySmall,
                    color = if (lookupPhase == LookupPhase.ERROR) colors.onErrorContainer else colors.onSurfaceVariant)
                if (lastScanAt > 0) Text("Последний скан: " + dateTime(lastScanAt),
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SourceChip(Icons.Outlined.Wifi, "Wi‑Fi " + wifiCount, wifiCount > 0)
            SourceChip(Icons.Outlined.SignalCellularAlt, "Соты " + cellCount, cellCount > 0)
            SourceChip(Icons.Outlined.Bluetooth, "BLE " + bleCount, bleCount > 0)
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.secondaryContainer) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (recording) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                    null, tint = colors.onSecondaryContainer, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (recording) "Запись точек включена" else "Локальная запись выключена",
                        fontWeight = FontWeight.SemiBold, color = colors.onSecondaryContainer)
                    Text(if (recording)
                        "Сканирование примерно раз в 15 секунд, пока приложение открыто. Наблюдения сохраняются на устройстве."
                    else "Включи запись, чтобы сохранять обнаруженные сети и их координаты локально.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSecondaryContainer)
                }
                Switch(checked = recording, onCheckedChange = onRecording)
            }
        }
        if (manualAnchor != null) {
            Text("Ручной ориентир: " + String.format(Locale.US, "%.5f, %.5f", manualAnchor.latitude, manualAnchor.longitude),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Divider(color = colors.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, null, tint = colors.primary); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Фильтр GNSS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(when (filterState) {
                    FilterState.WAITING.name -> "Ожидание данных"
                    FilterState.NORMAL.name -> "Аномалий не обнаружено"
                    FilterState.ZONE_RISK.name -> "В зоне риска"
                    FilterState.CORRECTING.name -> "Используется резервная позиция"
                    FilterState.NO_FALLBACK.name -> "Нет пригодного резерва"
                    else -> filterState
                }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text(riskScore.toString() + "%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        LinearProgressIndicator(progress = { riskScore.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth(),
            color = if (riskScore >= 60) colors.error else colors.primary, trackColor = colors.surfaceVariant)
        Text(serviceStatus.ifBlank { if (monitorEnabled) "Мониторинг активен." else "Фоновый мониторинг выключен." },
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onMonitoring, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Text(if (monitorEnabled) "Стоп мониторинг" else "Мониторинг")
            }
            FilledTonalButton(onClick = onMock, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Text(if (mockEnabled) "Выключить mock" else "Включить mock")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onOpenPoints, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.Storage, null); Spacer(Modifier.width(6.dp)); Text("База: " + pointCount)
            }
            OutlinedButton(onClick = onOpenZones, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.Shield, null); Spacer(Modifier.width(6.dp)); Text("Зоны")
            }
        }
        Text("Радиоидентификаторы не отправляются в BeaconDB или WiGLE. Карта загружается из OpenFreeMap и видит обычные сетевые метаданные.",
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun DatabaseScreen(
    points: List<RadioPointRecord>, history: List<RadioScanHistoryItem>, pointCount: Int, observationCount: Int,
    selectedView: PointsView, onView: (PointsView) -> Unit, onEdit: (RadioPointRecord) -> Unit,
    onDelete: (RadioPointRecord) -> Unit, onImport: () -> Unit, onExport: () -> Unit, onClear: () -> Unit, onRefresh: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Локальная база", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(pointCount.toString() + " точек · " + observationCount + " наблюдений",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Обновить список") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onView(PointsView.POINTS) }, label = { Text("Точки") }, leadingIcon = { Icon(Icons.Outlined.Storage, null) })
            AssistChip(onClick = { onView(PointsView.HISTORY) }, label = { Text("История") }, leadingIcon = { Icon(Icons.Outlined.History, null) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = onImport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Outlined.ImportExport, null); Spacer(Modifier.width(5.dp)); Text("Импорт WiGLE")
            }
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Outlined.ImportExport, null); Spacer(Modifier.width(5.dp)); Text("Экспорт CSV")
            }
            IconButton(onClick = onClear) { Icon(Icons.Outlined.DeleteOutline, "Удалить базу") }
        }
        Text(if (selectedView == PointsView.POINTS)
            "SSID не записываются при сканировании. Подпись — только вручную заданное имя или имя из импортированного CSV."
        else "Хранятся последние 500 сканирований. История остаётся на устройстве.",
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)

        if (selectedView == PointsView.POINTS) {
            if (points.isEmpty()) EmptyState("Точек пока нет", "Включи запись на карте или импортируй WiGLE CSV.")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(points, key = { it.id }) { point ->
                    RadioPointRow(point, onEdit = { onEdit(point) }, onDelete = { onDelete(point) })
                }
            }
        } else {
            if (history.isEmpty()) EmptyState("История пуста", "Первый запуск сканирования добавит сюда запись.")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(history, key = { it.id }) { entry ->
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh) {
                        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.History, null, tint = colors.primary); Spacer(Modifier.width(7.dp))
                                Text(dateTime(entry.time), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (entry.recorded) StatusPill("Запись", colors.tertiaryContainer, colors.onTertiaryContainer)
                            }
                            Text("Wi‑Fi ${entry.wifiCount} · соты ${entry.cellCount} · BLE ${entry.bleCount} · совпадений ${entry.matchedCount}",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            if (entry.latitude != null && entry.longitude != null) {
                                Text(String.format(Locale.US, "%.5f, %.5f · ±%.0f м", entry.latitude, entry.longitude, entry.accuracyMeters ?: 0.0),
                                    style = MaterialTheme.typography.bodySmall)
                            } else Text("Координаты не рассчитаны", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioPointRow(point: RadioPointRecord, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val typeLabel = when (point.type) { "wifi" -> "Wi‑Fi"; "cell" -> "Сотовая"; else -> "Bluetooth LE" }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), color = colors.surfaceContainerHigh) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = when (point.type) {
                "wifi" -> Color(0xFFD7F5E1); "cell" -> Color(0xFFF1DFFF); else -> Color(0xFFFFE8BE)
            }, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(when (point.type) {
                        "wifi" -> Icons.Outlined.Wifi
                        "cell" -> Icons.Outlined.SignalCellularAlt
                        else -> Icons.Outlined.Bluetooth
                    }, null, tint = Color(0xFF263248))
                }
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(point.label.ifBlank { typeLabel + " · " + point.identifier.takeLast(11) },
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(typeLabel + " · RSSI " + point.rssi + " dBm · " + point.sightings + " наблюдений",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(if (point.latitude != null && point.longitude != null)
                    String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude)
                else "Координаты не заданы", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (point.notes.isNotBlank()) Text(point.notes, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            }
            Column {
                IconButton(onClick = onEdit, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.Edit, "Редактировать точку") }
                IconButton(onClick = onDelete, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.DeleteOutline, "Удалить точку") }
            }
        }
    }
}

@Composable
private fun PointEditDialog(
    point: RadioPointRecord, onDismiss: () -> Unit,
    onSave: (String, String, Double?, Double?, Double?) -> Unit
) {
    var label by remember(point.id) { mutableStateOf(point.label) }
    var notes by remember(point.id) { mutableStateOf(point.notes) }
    var lat by remember(point.id) { mutableStateOf(point.latitude?.toString().orEmpty()) }
    var lon by remember(point.id) { mutableStateOf(point.longitude?.toString().orEmpty()) }
    var accuracy by remember(point.id) { mutableStateOf(point.accuracyMeters?.toString().orEmpty()) }
    var error by remember(point.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Редактировать радиоточку") },
        text = {
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(point.identifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = label, onValueChange = { label = it.take(100) }, label = { Text("Имя / заметка") }, singleLine = true)
                OutlinedTextField(value = notes, onValueChange = { notes = it.take(1_000) }, label = { Text("Заметки") }, minLines = 2)
                OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("Широта") }, singleLine = true)
                OutlinedTextField(value = lon, onValueChange = { lon = it }, label = { Text("Долгота") }, singleLine = true)
                OutlinedTextField(value = accuracy, onValueChange = { accuracy = it }, label = { Text("Точность, м") }, singleLine = true)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val latitude = lat.trim().takeIf { it.isNotBlank() }?.replace(',', '.')?.toDoubleOrNull()
                val longitude = lon.trim().takeIf { it.isNotBlank() }?.replace(',', '.')?.toDoubleOrNull()
                val acc = accuracy.trim().takeIf { it.isNotBlank() }?.replace(',', '.')?.toDoubleOrNull()
                if ((latitude == null) != (longitude == null)) error = "Укажи обе координаты или оставь обе пустыми."
                else if (latitude != null && (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                        longitude == null || !longitude.isFinite() || longitude !in -180.0..180.0)) error = "Координаты вне допустимого диапазона."
                else if (acc != null && (!acc.isFinite() || acc <= 0.0)) error = "Точность должна быть положительным числом."
                else onSave(label, notes, latitude, longitude, acc)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun ZonesScreen(
    zones: List<SavedRiskZone>, draftZone: List<ZonePoint>, mapTapMode: MapTapMode,
    onStartDraw: () -> Unit, onSaveDraft: () -> Unit,
    onToggle: (SavedRiskZone, Boolean) -> Unit, onRename: (SavedRiskZone) -> Unit,
    onDelete: (SavedRiskZone) -> Unit, onCancelDraw: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Зоны спуфинга", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Отмечай участки, где телефон показывает ложное или скачкообразное местоположение. Каждая зона хранится локально и может отдельно включаться в фильтре.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = onStartDraw, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Outlined.EditLocationAlt, null); Spacer(Modifier.width(6.dp))
                Text(if (mapTapMode == MapTapMode.DRAW_ZONE) "Рисование включено" else "Нарисовать зону")
            }
            OutlinedButton(onClick = onSaveDraft, enabled = draftZone.size >= 3, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                Text("Сохранить (" + draftZone.size + ")")
            }
        }
        if (mapTapMode == MapTapMode.DRAW_ZONE) {
            Text("Точек в черновике: " + draftZone.size + " · минимум 3. Нажимай на карту выше.",
                style = MaterialTheme.typography.labelMedium, color = colors.primary)
            TextButton(onClick = onCancelDraw) { Text("Отменить рисунок") }
        }
        if (zones.isEmpty()) EmptyState("Зон ещё нет", "Создай полигон вокруг места, где GNSS ведёт себя подозрительно.")
        zones.forEach { zone ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(17.dp), color = colors.surfaceContainerHigh) {
                Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Shield, null, tint = colors.primary); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(zone.name, fontWeight = FontWeight.SemiBold)
                            Text(zone.points.size.toString() + " вершин · " + if (zone.enabled) "участвует в фильтре" else "выключена",
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        Switch(checked = zone.enabled, onCheckedChange = { onToggle(zone, it) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { onRename(zone) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Outlined.Edit, null); Spacer(Modifier.width(4.dp)); Text("Название")
                        }
                        OutlinedButton(onClick = { onDelete(zone) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(4.dp)); Text("Удалить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(
    mode: PositionMode, mapTheme: MapThemeMode, useWifi: Boolean, useCells: Boolean, useBle: Boolean,
    showRadioPoints: Boolean, databaseFacts: String,
    onMode: (PositionMode) -> Unit, onMapTheme: (MapThemeMode) -> Unit,
    onWifi: (Boolean) -> Unit, onCells: (Boolean) -> Unit, onBle: (Boolean) -> Unit,
    onShowPoints: (Boolean) -> Unit, onManualAnchor: () -> Unit,
    onClearData: () -> Unit, onOpenLocationSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Настройки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Режим определения положения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        PositionMode.values().forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, fontWeight = FontWeight.Medium)
                    Text(when (item) {
                        PositionMode.AUTO -> "Android location, затем локальная радиобаза."
                        PositionMode.GPS_ONLY -> "Использовать только GPS-провайдер для текущей позиции."
                        PositionMode.RADIO_ONLY -> "Не запрашивать GPS. Совпадения по сохранённым радиоточкам."
                    }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                RadioButton(selected = mode == item, onClick = { onMode(item) })
            }
        }
        Divider(color = colors.outlineVariant)
        Text("Картографическая тема", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        MapThemeMode.values().forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(item.title, modifier = Modifier.weight(1f))
                RadioButton(selected = mapTheme == item, onClick = { onMapTheme(item) })
            }
        }
        Divider(color = colors.outlineVariant)
        Text("Источники для радиопозиции", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Переключатели влияют на расчёт положения. Новые сигналы записываются только при явно включённой записи.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        SettingSwitch("Wi‑Fi-точки", "BSSID и уровень сигнала; без SSID.", useWifi, onWifi)
        SettingSwitch("Мобильные соты", "GSM / WCDMA / LTE / NR, если доступны.", useCells, onCells)
        SettingSwitch("Bluetooth LE", "Только адрес и RSSI; имена устройств не читаются.", useBle, onBle)
        Divider(color = colors.outlineVariant)
        SettingSwitch("Радиоточки на карте", "До 400 последних координатных точек.", showRadioPoints, onShowPoints)
        OutlinedButton(onClick = onManualAnchor, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.LocationOn, null); Spacer(Modifier.width(8.dp)); Text("Поставить ручной ориентир на карте")
        }
        OutlinedButton(onClick = onOpenLocationSettings, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.GpsFixed, null); Spacer(Modifier.width(8.dp)); Text("Системные настройки геолокации")
        }
        Divider(color = colors.outlineVariant)
        Text("Приватность и хранение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Радиопозиционирование выполняется локально. BeaconDB и API WiGLE не вызываются; WiGLE — только формат ручного импорта/экспорта файла. SQLite не хранит SSID и имена Bluetooth-устройств.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Text(databaseFacts, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        OutlinedButton(onClick = onClearData, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("Удалить локальную базу")
        }
        Text("Карта: OpenFreeMap / OpenMapTiles / OpenStreetMap. Запросы тайлов идут к картографическому серверу, который может видеть IP-адрес и запрашиваемые участки карты. Радиоидентификаторы туда не отправляются.",
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ClearGpsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        darkColorScheme(primary = Color(0xFF9BBEFF), secondary = Color(0xFFBFC7DC),
            tertiary = Color(0xFFE7B9FA), surface = Color(0xFF121722), background = Color(0xFF0E121B))
    } else {
        lightColorScheme(primary = Color(0xFF285DB4), secondary = Color(0xFF515F79),
            tertiary = Color(0xFF725580), surface = Color(0xFFF7F8FC), background = Color(0xFFF1F3F8))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun HeaderCard(tab: AppTab, onCenter: () -> Unit, onSettings: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(25.dp), color = colors.surface.copy(alpha = 0.95f),
        tonalElevation = 4.dp, shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = colors.primaryContainer, modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.GpsFixed, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Clear GPS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(when (tab) {
                    AppTab.MAP -> "Локальная навигация · GNSS"
                    AppTab.POINTS -> "Радиоточки и история"
                    AppTab.ZONES -> "Зоны аномалий"
                    AppTab.SETTINGS -> "Источники и приватность"
                }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            IconButton(onClick = onCenter) { Icon(Icons.Outlined.MyLocation, "Моё местоположение") }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Настройки") }
        }
    }
}

@Composable
private fun ModeBanner(text: String, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), color = colors.tertiaryContainer, shape = RoundedCornerShape(18.dp), shadowElevation = 3.dp) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.EditLocationAlt, null, tint = colors.onTertiaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onTertiaryContainer)
            IconButton(onClick = onCancel) { Icon(Icons.Outlined.DeleteOutline, "Отменить режим", tint = colors.onTertiaryContainer) }
        }
    }
}

@Composable
private fun StatusPill(text: String, color: Color, foreground: Color) {
    Surface(shape = CircleShape, color = color) {
        Text(text, modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium, color = foreground, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SourceChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, active: Boolean) {
    val colors = MaterialTheme.colorScheme
    AssistChip(onClick = { }, enabled = false, label = { Text(text, maxLines = 1) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(17.dp)) }, shape = RoundedCornerShape(14.dp),
        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (active) colors.secondaryContainer else colors.surfaceVariant,
            disabledLabelColor = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant,
            disabledLeadingIconContentColor = if (active) colors.onSecondaryContainer else colors.onSurfaceVariant
        ))
}

@Composable
private fun EmptyState(title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun MapAttribution(modifier: Modifier = Modifier, dark: Boolean, onClick: () -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(9.dp),
        color = (if (dark) Color(0xFF222631) else Color.White).copy(alpha = 0.92f), shadowElevation = 2.dp, onClick = onClick) {
        Text("© OpenFreeMap · © OSM", modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            color = if (dark) Color.White else Color(0xFF253044), style = MaterialTheme.typography.labelSmall)
    }
}

private fun currentReference(location: Location?, anchor: ManualAnchor?): RadioReference? {
    val now = System.currentTimeMillis()
    if (location != null && now - location.time in 0..60_000L && !isMockLocation(location) &&
        location.hasAccuracy() && location.accuracy in 1f..500f) {
        return RadioReference(location.latitude, location.longitude, location.accuracy.toDouble(), "android-location")
    }
    return anchor?.let { RadioReference(it.latitude, it.longitude, 1_500.0, "manual-anchor") }
}
private fun hasAnyLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
private fun isMockLocation(location: Location): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
private fun isBetterLocation(candidate: Location, previous: Location): Boolean {
    val delta = candidate.time - previous.time
    val candidateAcc = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
    val previousAcc = if (previous.hasAccuracy()) previous.accuracy else Float.MAX_VALUE
    if (candidate.provider.equals(LocationManager.GPS_PROVIDER, true) && !previous.provider.equals(LocationManager.GPS_PROVIDER, true)) return delta >= -120_000L
    if (previous.provider.equals(LocationManager.GPS_PROVIDER, true) && candidate.provider.equals(LocationManager.NETWORK_PROVIDER, true) && delta < 120_000L) return false
    if (delta > 30_000L) return true
    if (delta < -120_000L) return false
    return candidateAcc < previousAcc || (delta >= 0L && candidateAcc <= previousAcc * 1.5f)
}
private fun dateTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
private const val RECORD_INTERVAL_MS = 15_000L
