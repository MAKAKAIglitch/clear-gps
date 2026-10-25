package org.cleargps

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditLocationAlt
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.material3.Slider
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cleargps.core.FilterState
import org.cleargps.core.SavedRiskZone
import org.cleargps.core.ZonePoint
import org.cleargps.core.ZoneGeometry
import org.cleargps.core.ZoneStore
import org.maplibre.android.maps.MapView
import java.io.BufferedInputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class AppTab { MAP, POINTS, ZONES, OBD2, SETTINGS }
private enum class PointsView { POINTS, HISTORY }
private enum class DatabaseDataSource(val key: String, val title: String) {
    LOCAL("local", "Локальная"),
    BEACONDB("beacondb", "BeaconDB · вышки")
}
private enum class RadioPointTypeFilter(val title: String, val type: String?) {
    ALL("Все", null),
    WIFI("Wi‑Fi", "wifi"),
    CELL("Вышки", "cell"),
    BLE("Bluetooth", "ble")
}
private enum class LookupPhase { IDLE, SCANNING, UPDATED, STALE, NO_MATCH, ERROR }
private enum class PositionMode(
    val key: String,
    val title: String,
    val usesRadio: Boolean = false,
    val gpsOnly: Boolean = false
) {
    AUTO("auto", "Авто"),
    GPS_ONLY("gps", "GPS", gpsOnly = true),
    RADIO_ONLY("radio", "Только радио", usesRadio = true)
}

private fun loadPositionMode(prefs: android.content.SharedPreferences): PositionMode {
    val storedKey = prefs.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto") ?: "auto"
    val normalizedKey = when (storedKey) {
        "gps_obd2" -> "gps"
        "radio_obd2" -> "radio"
        else -> storedKey
    }
    val mode = PositionMode.values().firstOrNull { it.key == normalizedKey } ?: PositionMode.AUTO
    if (storedKey != mode.key) {
        prefs.edit().putString(ClearGpsPreferences.KEY_POSITION_MODE, mode.key).apply()
    }
    return mode
}
private enum class MapThemeMode(val key: String, val title: String) {
    SYSTEM("system", "Система"), LIGHT("light", "Светлая"), DARK("dark", "Тёмная")
}
private enum class MapSourceMode(val key: String, val title: String) {
    ONLINE("online", "OpenFreeMap"), OFFLINE("offline", "Без внешних тайлов")
}

private fun readPublishedMockCoordinate(prefs: android.content.SharedPreferences): MapCoordinate? {
    if (!prefs.getBoolean(LocationMonitorService.KEY_HAS_OUTPUT, false) ||
        !prefs.getBoolean(LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE, false)) return null
    val latitude = prefs.all[LocationMonitorService.KEY_LAT]?.toString()?.toDoubleOrNull() ?: return null
    val longitude = prefs.all[LocationMonitorService.KEY_LON]?.toString()?.toDoubleOrNull() ?: return null
    if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
        !longitude.isFinite() || longitude !in -180.0..180.0) return null
    return MapCoordinate(latitude, longitude)
}

private fun readPublishedMockAccuracy(prefs: android.content.SharedPreferences): Double? {
    if (!prefs.getBoolean(LocationMonitorService.KEY_HAS_OUTPUT, false) ||
        !prefs.getBoolean(LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE, false)) return null
    return prefs.all[LocationMonitorService.KEY_ACCURACY]?.toString()?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > 0.0 && it <= 100_000.0 }
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
    val obd2Repository = remember { Obd2Repository(context.applicationContext) }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()

    var appVisible by remember { mutableStateOf(true) }
    var positionMode by remember {
        mutableStateOf(loadPositionMode(prefs))
    }
    var mapThemeMode by remember {
        mutableStateOf(MapThemeMode.values().firstOrNull { it.key == prefs.getString(ClearGpsPreferences.KEY_MAP_STYLE, "system") } ?: MapThemeMode.SYSTEM)
    }
    var mapSourceMode by remember {
        mutableStateOf(MapSourceMode.values().firstOrNull { it.key == prefs.getString(ClearGpsPreferences.KEY_MAP_SOURCE, "online") } ?: MapSourceMode.ONLINE)
    }
    val panelFraction = remember { Animatable(0.46f) }
    var panelVisible by remember { mutableStateOf(true) }
    var backExitArmed by remember { mutableStateOf(false) }
    var pendingMonitorStart by remember { mutableStateOf(false) }
    var pendingMockEnable by remember { mutableStateOf(false) }
    val mapIsDark = when (mapThemeMode) {
        MapThemeMode.SYSTEM -> systemDark
        MapThemeMode.LIGHT -> false
        MapThemeMode.DARK -> true
    }

    var liveLocation by remember { mutableStateOf<Location?>(null) }
    var currentRadioEstimate by remember { mutableStateOf<RadioEstimate?>(null) }
    // Mirror the exact coordinate/accuracy last accepted by the foreground service so the
    // in-app map follows the same fix sent to Android's mock provider.
    var publishedMockCoordinate by remember { mutableStateOf(readPublishedMockCoordinate(prefs)) }
    var publishedMockAccuracy by remember { mutableStateOf(readPublishedMockAccuracy(prefs)) }
    // Focus the map once when a radio mode gets its first estimate; don't fight manual panning.
    var pendingRadioMapFocus by remember { mutableStateOf(positionMode.usesRadio) }
    var manualAnchor by remember { mutableStateOf(ManualLocationStore.load(context)) }
    var manualFallbackEnabled by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_MANUAL_FALLBACK_ENABLED, false)) }
    var zones by remember { mutableStateOf(ZoneStore.loadAll(context)) }
    var draftZone by remember { mutableStateOf(emptyList<ZonePoint>()) }
    var mapTapMode by remember { mutableStateOf(MapTapMode.NONE) }
    var selectedTab by remember { mutableStateOf(AppTab.MAP) }
    var obd2Adapters by remember { mutableStateOf(emptyList<Obd2Adapter>()) }
    var selectedObd2Address by remember {
        mutableStateOf(prefs.getString(Obd2SharedState.KEY_ADAPTER_ADDRESS, "").orEmpty())
    }
    var obd2Connected by remember { mutableStateOf(false) }
    var obd2Connecting by remember { mutableStateOf(false) }
    var obd2Telemetry by remember { mutableStateOf(Obd2Telemetry()) }
    var obd2Status by remember { mutableStateOf("Не подключено. Выбери сопряжённый Bluetooth OBD-II адаптер.") }
    var obd2StatusIsError by remember { mutableStateOf(false) }
    var fusionNowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var pointsView by remember { mutableStateOf(PointsView.POINTS) }
    var points by remember { mutableStateOf(database.listPoints(500)) }
    var mapPoints by remember { mutableStateOf(emptyList<RadioPointRecord>()) }
    var mapPointsRevision by remember { mutableStateOf(0) }
    var history by remember { mutableStateOf(database.listHistory(150)) }
    var pointCount by remember { mutableStateOf(database.pointCount()) }
    var observationCount by remember { mutableStateOf(database.observationCount()) }
    var searchQuery by remember { mutableStateOf("") }
    var pointTypeFilter by remember { mutableStateOf(RadioPointTypeFilter.ALL) }
    var selectedMapPoint by remember { mutableStateOf<RadioPointRecord?>(null) }
    var selectedPointObservations by remember { mutableStateOf(emptyList<RadioPointObservation>()) }
    var selectedPointCenter by remember { mutableStateOf<RadioPointCenter?>(null) }
    var showRadioPointsOnMap by remember { mutableStateOf(true) }
    var databaseDataSource by remember {
        mutableStateOf(
            DatabaseDataSource.values().firstOrNull {
                it.key == prefs.getString(ClearGpsPreferences.KEY_DATABASE_VIEW_SOURCE, "local")
            } ?: DatabaseDataSource.LOCAL
        )
    }

    var useWifi by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_USE_WIFI, true)) }
    var useCells by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_USE_CELLS, true)) }
    var useBle by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_USE_BLE, true)) }
    var scanIntervalSeconds by remember { mutableStateOf(prefs.getInt(ClearGpsPreferences.KEY_SCAN_INTERVAL_SECONDS, 15).coerceIn(1, 30)) }
    var adaptiveScan by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_ADAPTIVE_SCAN, true)) }
    var recording by remember {
        mutableStateOf(!positionMode.gpsOnly && prefs.getBoolean(ClearGpsPreferences.KEY_RECORDING, false))
    }
    var scanBusy by remember { mutableStateOf(false) }
    var explicitRadioScanJob by remember { mutableStateOf<Job?>(null) }
    var scanMessage by remember {
        mutableStateOf("Сканирование локальное. Запиши радиоточки на известном месте, чтобы затем ориентироваться по ним без GPS.")
    }
    var lookupPhase by remember { mutableStateOf(LookupPhase.IDLE) }
    var wifiCount by remember { mutableStateOf(prefs.getInt(ClearGpsPreferences.KEY_LAST_WIFI_COUNT, 0)) }
    var cellCount by remember { mutableStateOf(prefs.getInt(ClearGpsPreferences.KEY_LAST_CELL_COUNT, 0)) }
    var bleCount by remember { mutableStateOf(prefs.getInt(ClearGpsPreferences.KEY_LAST_BLE_COUNT, 0)) }
    var lastScanAt by remember { mutableStateOf(prefs.getLong(ClearGpsPreferences.KEY_LAST_SCAN_AT, 0L)) }
    var recordedCount by remember { mutableStateOf(prefs.getInt(ClearGpsPreferences.KEY_LAST_RECORDED_COUNT, 0)) }
    var beaconDbEnabled by remember {
        mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, false))
    }
    var beaconDbLoading by remember { mutableStateOf(false) }
    var beaconDbLookupResult by remember { mutableStateOf<BeaconDbLookupResult?>(null) }
    var beaconDbMessage by remember {
        mutableStateOf("BeaconDB отключён. Включи разрешение в настройках, если захочешь выполнить ручной запрос.")
    }
    var beaconDbLookupJob by remember { mutableStateOf<Job?>(null) }
    var beaconDbRequestToken by remember { mutableStateOf(0) }
    var monitorEnabled by remember { mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)) }
    var mockEnabled by remember { mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)) }
    var mockOutputActive by remember { mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE, false)) }
    var filterState by remember { mutableStateOf(prefs.getString(LocationMonitorService.KEY_STATE, FilterState.WAITING.name) ?: FilterState.WAITING.name) }
    var riskScore by remember { mutableStateOf(prefs.getInt(LocationMonitorService.KEY_SCORE, 0)) }
    var riskBreakdown by remember {
        mutableStateOf(RiskBreakdownStore.read(prefs, SystemClock.elapsedRealtimeNanos()))
    }
    var riskReasons by remember {
        mutableStateOf((prefs.getString(LocationMonitorService.KEY_REASONS, "") ?: "").split('\n').filter { it.isNotBlank() })
    }
    var serviceStatus by remember { mutableStateOf(prefs.getString(LocationMonitorService.KEY_SERVICE_STATUS, "") ?: "") }
    var fusedMockStatus by remember {
        mutableStateOf(prefs.getString(LocationMonitorService.KEY_FUSED_MOCK_STATUS, "") ?: "")
    }
    var gnssEnvironment by remember {
        mutableStateOf(prefs.getString(LocationMonitorService.KEY_GNSS_ENVIRONMENT, "UNKNOWN") ?: "UNKNOWN")
    }
    var gnssEnvironmentReasons by remember {
        mutableStateOf((prefs.getString(LocationMonitorService.KEY_GNSS_ENVIRONMENT_REASONS, "") ?: "").split('\n').filter { it.isNotBlank() })
    }
    var gnssBaselineReady by remember {
        mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_GNSS_BASELINE_READY, false))
    }
    var gnssBaselineDeviations by remember {
        mutableStateOf((prefs.getString(LocationMonitorService.KEY_GNSS_BASELINE_DEVIATIONS, "") ?: "").split('\n').filter { it.isNotBlank() })
    }
    var gnssDiagnosticsAtNanos by remember {
        mutableStateOf(prefs.getLong(LocationMonitorService.KEY_GNSS_DIAGNOSTICS_AT_NANOS, 0L))
    }
    var gnssDiagnosticsNowNanos by remember { mutableStateOf(SystemClock.elapsedRealtimeNanos()) }
    var gnssLiveSnapshot by remember {
        mutableStateOf(GnssDiagnosticsLiveStore.snapshot(SystemClock.elapsedRealtimeNanos()))
    }
    var detailedLogging by remember { mutableStateOf(prefs.getBoolean(DiagnosticLogStore.KEY_ENABLED, false)) }
    var diagnosticLogMaxKb by remember {
        mutableStateOf(prefs.getInt(DiagnosticLogStore.KEY_MAX_KB, DiagnosticLogStore.DEFAULT_MAX_KB)
            .coerceIn(DiagnosticLogStore.MIN_MAX_KB, DiagnosticLogStore.MAX_MAX_KB))
    }
    var showDiagnosticLogs by remember { mutableStateOf(false) }
    var diagnosticLogText by remember { mutableStateOf("") }

    var pointToEdit by remember { mutableStateOf<RadioPointRecord?>(null) }
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showExportOptions by remember { mutableStateOf(false) }
    var showZoneNameDialog by remember { mutableStateOf(false) }
    var zoneName by remember { mutableStateOf("") }
    var zoneToRename by remember { mutableStateOf<SavedRiskZone?>(null) }
    var zoneRenameText by remember { mutableStateOf("") }
    var showRadioOnlyInfo by remember { mutableStateOf(false) }
    var pendingRecordScan by remember { mutableStateOf(false) }

    // Load and publish the local database UI from the caller's existing coroutine.
    // A scan must not start a nested rememberCoroutineScope job: that scope may already
    // have left the composition by the time an asynchronous scan returns.
    suspend fun refreshLocalUiInCurrentCoroutine() {
        val query = searchQuery
        points = withContext(Dispatchers.IO) {
            if (query.isBlank()) database.listPoints(500) else database.searchPoints(query, 500)
        }
        mapPoints = withContext(Dispatchers.IO) { database.listMapPoints(recommendedMapPointLimit(context)) }
        mapPointsRevision++
        history = withContext(Dispatchers.IO) { database.listHistory(150) }
        pointCount = withContext(Dispatchers.IO) { database.pointCount() }
        observationCount = withContext(Dispatchers.IO) { database.observationCount() }
        zones = ZoneStore.loadAll(context)
    }

    fun refreshLocalUi() {
        scope.launch { refreshLocalUiInCurrentCoroutine() }
    }

    fun navigateToTab(tab: AppTab) {
        selectedTab = tab
        panelVisible = true
        scope.launch { panelFraction.animateTo(0.82f, animationSpec = tween(220)) }
        if (tab == AppTab.POINTS) refreshLocalUi()
    }

    fun openRadioPointDetails(pointId: Long) {
        scope.launch {
            selectedMapPoint = null
            selectedPointObservations = emptyList()
            selectedPointCenter = null
            val loaded = withContext(Dispatchers.IO) {
                val point = database.findPoint(pointId) ?: return@withContext null
                point to database.listObservations(point)
            }
            val data = loaded ?: return@launch
            val point = data.first
            val observations = data.second
            val center = estimateObservationCenter(observations)
            selectedMapPoint = point
            selectedPointObservations = observations
            selectedPointCenter = center
            val focus = center?.let { MapCoordinate(it.latitude, it.longitude) }
                ?: if (point.latitude != null && point.longitude != null) {
                    MapCoordinate(point.latitude, point.longitude)
                } else null
            if (focus != null) mapController.focus(focus, 16.0)
        }
    }

    fun closeRadioPointDetails() {
        selectedMapPoint = null
        selectedPointObservations = emptyList()
        selectedPointCenter = null
    }

    fun saveUseFlags() {
        prefs.edit().putBoolean(ClearGpsPreferences.KEY_USE_WIFI, useWifi)
            .putBoolean(ClearGpsPreferences.KEY_USE_CELLS, useCells)
            .putBoolean(ClearGpsPreferences.KEY_USE_BLE, useBle).apply()
    }

    fun applyPositionMode(mode: PositionMode) {
        if (mode.gpsOnly) {
            // Switching to GPS-only must stop any in-flight UI radio scan and turn off
            // persistent radio recording so it cannot silently resume later.
            explicitRadioScanJob?.cancel()
            explicitRadioScanJob = null
            if (recording) {
                recording = false
                prefs.edit().putBoolean(ClearGpsPreferences.KEY_RECORDING, false).apply()
            }
            scanMessage = "Радиосканирование отключено в режиме GPS."
            lookupPhase = LookupPhase.IDLE
        }
        positionMode = mode
        prefs.edit().putString(ClearGpsPreferences.KEY_POSITION_MODE, mode.key).apply()
        currentRadioEstimate = null
        publishedMockCoordinate = null
        publishedMockAccuracy = null
        pendingRadioMapFocus = mode.usesRadio
        if (mode.usesRadio || mode.gpsOnly) liveLocation = null
        if (monitorEnabled) {
            val intent = Intent(context, LocationMonitorService::class.java)
                .setAction(LocationMonitorService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
        if (mode.usesRadio) showRadioOnlyInfo = true
    }

    val performScan: suspend (Boolean) -> Unit = { recordNow ->
        if (positionMode.gpsOnly) {
            // Hard guard: no route to RadioGeolocationRepository from GPS-only mode,
            // including stale callbacks that fire after the user changes mode.
            scanMessage = "Радиосканирование отключено в режиме GPS."
            lookupPhase = LookupPhase.IDLE
        } else if (!scanBusy) {
            scanBusy = true
            lookupPhase = LookupPhase.SCANNING
            scanMessage = if (recordNow) "Сохраняю наблюдения в локальную SQLite-базу…" else "Сканирую Wi‑Fi, мобильные соты и Bluetooth LE…"
            try {
                val now = System.currentTimeMillis()
                // An estimate derived from the local database is not a ground-truth reference.
                // Never feed it back into the database as a newly "known" coordinate.
                val trustedRadioEstimate = currentRadioEstimate?.takeIf {
                    val age = now - it.updatedAt
                    age in 0..30L * 60L * 1_000L && it.matchedCount >= 3 && it.accuracyMeters <= 1_000.0
                }
                val reference = if (positionMode.usesRadio) null
                    else currentReference(liveLocation, manualAnchor)
                val observedPosition = if (positionMode.usesRadio) {
                    trustedRadioEstimate?.let { ZonePoint(it.latitude, it.longitude) }
                } else {
                    liveLocation?.takeIf { now - it.time in 0..30_000L }
                        ?.let { ZonePoint(it.latitude, it.longitude) }
                }
                val insideRiskZone = observedPosition?.let { point ->
                    zones.any { zone -> zone.enabled && ZoneGeometry.contains(point, zone.points) }
                } == true
                val result = repository.scan(
                    record = recordNow && !insideRiskZone,
                    reference = if (insideRiskZone) null else reference,
                    mode = positionMode.key, useWifi = useWifi, useCells = useCells, useBle = useBle
                )
                wifiCount = result.wifiCount
                cellCount = result.cellCount
                bleCount = result.beaconCount
                recordedCount = result.recordedCount
                lastScanAt = System.currentTimeMillis()
                val nowAfterScan = System.currentTimeMillis()
                val lastCredibleEstimate = currentRadioEstimate?.takeIf {
                    RadioFixReliability.canDisplayLastRadioEstimateOnMap(
                        it.updatedAt, nowAfterScan, it.matchedCount, it.accuracyMeters
                    )
                }
                // A single throttled/empty radio scan is not evidence that the previously
                // confirmed coordinate vanished. Keep it briefly, with an explicit stale status,
                // instead of flickering the map and mock UI to "no fix" every few seconds.
                currentRadioEstimate = result.estimate ?: lastCredibleEstimate
                val retainedHint = if (result.estimate == null && lastCredibleEstimate != null) {
                    val ageSeconds = ((nowAfterScan - lastCredibleEstimate.updatedAt).coerceAtLeast(0L) / 1_000L)
                    " Последняя подтверждённая радио-оценка сохранена на экране (${ageSeconds} с); текущий скан её не подтвердил."
                } else ""
                scanMessage = result.message +
                    (if (recordNow && result.recordedCount > 0) " Сохранено/обновлено записей: " + result.recordedCount + "." else "") +
                    (if (recordNow && insideRiskZone) " Запись точек отключена: позиция попала в зону риска." else "") +
                    retainedHint
                lookupPhase = when {
                    result.estimate != null -> LookupPhase.UPDATED
                    lastCredibleEstimate != null -> LookupPhase.STALE
                    result.wifiCount + result.cellCount + result.beaconCount == 0 -> LookupPhase.ERROR
                    else -> LookupPhase.NO_MATCH
                }
                refreshLocalUiInCurrentCoroutine()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                // Leaving composition cancels the scan. Do not misreport cancellation as a
                // radio/GPS scan failure, and never launch a new remembered scope from here.
                throw cancelled
            } catch (error: SecurityException) {
                DiagnosticLogStore.log(context, "RADIO_UI", "Сканирование отклонено Android: ${error.message ?: "SecurityException"}", "ERROR")
                scanMessage = "Android запретил сканирование. Проверь разрешения и системные настройки геолокации."
                lookupPhase = LookupPhase.ERROR
            } catch (error: Exception) {
                DiagnosticLogStore.log(context, "RADIO_UI", "Ошибка локального сканирования: ${error.javaClass.simpleName}: ${error.message ?: "без описания"}", "ERROR")
                scanMessage = "Ошибка локального сканирования: " + (error.message ?: "неизвестная ошибка")
                lookupPhase = LookupPhase.ERROR
            } finally {
                scanBusy = false
            }
        }
    }

    fun launchRadioScan(recordNow: Boolean, afterScan: (() -> Unit)? = null) {
        if (positionMode.gpsOnly) {
            scanMessage = "Радиосканирование отключено в режиме GPS."
            lookupPhase = LookupPhase.IDLE
            return
        }
        if (scanBusy || explicitRadioScanJob?.isActive == true) return
        explicitRadioScanJob = scope.launch {
            performScan(recordNow)
            afterScan?.invoke()
        }
    }

    // Selecting radio-only immediately calculates an estimate from the local radio database.
    // This effect never asks Android for a location fix; it only scans radio identifiers.
    LaunchedEffect(positionMode, useWifi, useCells, useBle, hasAnyLocationPermission(context), monitorEnabled) {
        // When the foreground service is active it owns periodic radio scans; avoid a second
        // UI scan competing for the same Android Wi-Fi/BLE scan budget.
        if (positionMode.usesRadio && !monitorEnabled && hasAnyLocationPermission(context)) {
            // A scan may still be finishing from the previous mode. Wait rather than silently
            // skipping the first radio lookup when scanBusy is already true.
            while (scanBusy && positionMode.usesRadio) delay(100L)
            if (positionMode.usesRadio && hasAnyLocationPermission(context)) {
                performScan(false)
            }
        }
    }

    // Ask for the app's runtime permissions as soon as the app opens.
    // ACCESS_MOCK_LOCATION is not a runtime permission; its manifest declaration makes
    // Clear GPS eligible for Android's Developer options mock-location picker.
    val startupPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val missing = mutableListOf<String>()
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += "точная геолокация"
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += "геолокация"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing += "поиск Bluetooth-устройств"
            }
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing += "доступ к Bluetooth"
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing += "уведомления"
        }
        if (missing.isNotEmpty()) {
            Toast.makeText(
                context,
                "Не все разрешения выданы: " + missing.distinct().joinToString(", ") +
                    ". Часть функций будет ограничена.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        launchRadioScan(pendingRecordScan) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && result[Manifest.permission.BLUETOOTH_SCAN] != true) {
                scanMessage += " Bluetooth недоступен без разрешения, но Wi‑Fi и сотовое сканирование продолжены."
            }
        }
    }

    fun startMonitoringWithPermission(enableMock: Boolean) {
        try {
            prefs.edit().putBoolean(LocationMonitorService.KEY_MONITORING, true)
                .putBoolean(LocationMonitorService.KEY_MOCK_ENABLED,
                    enableMock || prefs.getBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)).apply()
            val intent = Intent(context, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            monitorEnabled = true
            serviceStatus = "Мониторинг запущен"
            if (enableMock) {
                mockEnabled = true
                Toast.makeText(context, "Выбери Clear GPS как приложение фиктивного местоположения в настройках разработчика.", Toast.LENGTH_LONG).show()
            }
        } catch (error: Exception) {
            prefs.edit().putBoolean(LocationMonitorService.KEY_MONITORING, false)
                .putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false).apply()
            monitorEnabled = false; mockEnabled = false
            serviceStatus = "Не удалось запустить мониторинг: " + (error.message ?: error.javaClass.simpleName)
            Toast.makeText(context, serviceStatus, Toast.LENGTH_LONG).show()
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (pendingMonitorStart) {
            pendingMonitorStart = false
            if (hasAnyLocationPermission(context)) {
                startMonitoringWithPermission(pendingMockEnable)
                pendingMockEnable = false
            } else {
                pendingMockEnable = false
                Toast.makeText(context, "Мониторинг не запущен: разрешение геолокации отклонено.", Toast.LENGTH_LONG).show()
            }
        } else if (positionMode.gpsOnly) {
            scanMessage = "Радиосканирование отключено в режиме GPS."
            lookupPhase = LookupPhase.IDLE
        } else if (result.values.any { it }) {
            if (useBle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN))
            } else {
                launchRadioScan(pendingRecordScan)
            }
        } else {
            scanMessage = "Без разрешения геолокации Android ограничивает сканирование Wi‑Fi и сотовых сетей."
            lookupPhase = LookupPhase.ERROR
        }
    }

    val startScan: (Boolean) -> Unit = { recordNow ->
        if (positionMode.gpsOnly) {
            scanMessage = "Радиосканирование отключено в режиме GPS."
            lookupPhase = LookupPhase.IDLE
        } else {
            pendingRecordScan = recordNow
            if (!hasAnyLocationPermission(context)) {
                locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            } else if (useBle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN))
            } else {
                launchRadioScan(recordNow)
            }
        }
    }

    fun startBeaconDbLookup() {
        if (!beaconDbEnabled) {
            beaconDbMessage = "BeaconDB отключён в настройках. Сетевого запроса не было."
            return
        }
        if (beaconDbLoading || beaconDbLookupJob?.isActive == true) return

        val requestToken = beaconDbRequestToken + 1
        beaconDbRequestToken = requestToken
        beaconDbLoading = true
        beaconDbMessage = "Читаю только текущие сотовые ячейки; запрос ещё не отправлен…"
        val queryJob = scope.launch {
            try {
                val result = repository.lookupBeaconDbCellsOnly()
                if (requestToken == beaconDbRequestToken && beaconDbEnabled) {
                    beaconDbLookupResult = result
                    beaconDbMessage = result.message
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestToken == beaconDbRequestToken && beaconDbEnabled) {
                    beaconDbMessage = "Ошибка BeaconDB: " + error.javaClass.simpleName +
                        ". Локальная база не изменена."
                    beaconDbLookupResult = null
                }
            } finally {
                if (requestToken == beaconDbRequestToken) {
                    beaconDbLoading = false
                    beaconDbLookupJob = null
                }
            }
        }
        beaconDbLookupJob = queryJob
    }

    fun publishBeaconDbEstimateAsMock() {
        if (!beaconDbEnabled) {
            beaconDbMessage = "BeaconDB отключён. Запрос на публикацию не отправлялся."
            return
        }
        val coordinate = beaconDbLookupResult?.coordinate
        if (coordinate == null) {
            beaconDbMessage = "Нет пригодной оценки BeaconDB для публикации."
            return
        }
        if (!monitorEnabled || !mockEnabled) {
            beaconDbMessage = "Сначала включи «Включить фикс» на главном экране и Mock GPS. Оценка не опубликована."
            Toast.makeText(
                context,
                "Сначала включи «Включить фикс» и Mock GPS на главном экране.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        try {
            val intent = Intent(context, LocationMonitorService::class.java).apply {
                action = LocationMonitorService.ACTION_PUBLISH_BEACONDB_FIX
                putExtra(LocationMonitorService.EXTRA_BEACONDB_LATITUDE, coordinate.latitude)
                putExtra(LocationMonitorService.EXTRA_BEACONDB_LONGITUDE, coordinate.longitude)
                putExtra(LocationMonitorService.EXTRA_BEACONDB_ACCURACY, coordinate.accuracyMeters)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            beaconDbMessage =
                "Запрошена публикация результата через Mock GPS + Network на 2 минуты. " +
                "Новые запросы к BeaconDB не выполняются; погрешность будет консервативно увеличиваться."
        } catch (error: Exception) {
            beaconDbMessage = "Не удалось запустить публикацию mock-координат: " +
                (error.message ?: error.javaClass.simpleName)
        }
    }

    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val csv = withContext(Dispatchers.IO) { database.exportWigleCsv() }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                        ?: throw IllegalStateException("Не удалось открыть файл для записи.")
                }
                Toast.makeText(context, "WiGLE CSV сохранён. История наблюдений включена; отправки нет.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, "Ошибка экспорта: " + (error.message ?: "неизвестная ошибка"), Toast.LENGTH_LONG).show()
            }
        }
    }
    val exportSqliteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException("Не удалось открыть файл для записи.")
                    output.use { database.exportSqliteBackup(it) }
                }
                Toast.makeText(context, "SQLite-резервная копия Clear GPS сохранена.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, "Ошибка SQLite-экспорта: " + (error.message ?: "неизвестная ошибка"), Toast.LENGTH_LONG).show()
            }
        }
    }
    val exportWigleSqliteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = context.contentResolver.openOutputStream(uri, "w")
                        ?: throw IllegalStateException("Не удалось открыть файл для записи.")
                    output.use { database.exportWigleSqlite(it) }
                }
                Toast.makeText(context, "WiGLE SQLite сохранён: сети и геолокированные наблюдения.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, "Ошибка WiGLE SQLite-экспорта: " + (error.message ?: "неизвестная ошибка"), Toast.LENGTH_LONG).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Не удалось открыть файл.")
                    val buffered = BufferedInputStream(input)
                    buffered.mark(16)
                    val signature = ByteArray(16)
                    var signatureBytes = 0
                    while (signatureBytes < signature.size) {
                        val read = buffered.read(signature, signatureBytes, signature.size - signatureBytes)
                        if (read < 0) break
                        signatureBytes += read
                    }
                    buffered.reset()
                    val isSqlite = signatureBytes == 16 &&
                        String(signature, Charsets.US_ASCII).startsWith("SQLite format 3\u0000")
                    if (isSqlite) database.importSqliteDatabase(buffered) else database.importWigleCsv(buffered)
                }
                refreshLocalUi()
                Toast.makeText(
                    context,
                    "Формат: " + result.format + ". Новых наблюдений: " + result.observationsImported +
                        "; устройств: " + result.uniqueRadios + "; новых устройств: " + result.newRadios,
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                Toast.makeText(context, "Ошибка импорта: " + (error.message ?: "неизвестная ошибка"), Toast.LENGTH_LONG).show()
            }
        }
    }

    suspend fun loadPairedObdAdapters() {
        try {
            val available = obd2Repository.pairedAdapters()
            obd2Adapters = available
            if (available.none { it.address.equals(selectedObd2Address, ignoreCase = true) }) {
                selectedObd2Address = available.firstOrNull()?.address.orEmpty()
                prefs.edit().putString(Obd2SharedState.KEY_ADAPTER_ADDRESS, selectedObd2Address).apply()
            }
            val previousFailureCanNowBeCleared =
                obd2Status.startsWith("Не найден сопряжённый OBD-II адаптер.") ||
                    obd2Status.startsWith("Без разрешения «Устройства поблизости»")
            when {
                available.isEmpty() -> {
                    obd2StatusIsError = true
                    obd2Status = "Не найден сопряжённый OBD-II адаптер. Пробуем резервный вариант: выбранный GPS/радио источник продолжает работать без автомобильной скорости. Сопряги адаптер в настройках Bluetooth."
                }
                obd2Connected && obd2Telemetry.updatedAt > 0L -> {
                    // Refreshing the paired-device list must not hide a live speed-reading
                    // error or overwrite a valid telemetry status with an unrelated message.
                }
                obd2StatusIsError && !previousFailureCanNowBeCleared -> {
                    // Keep a connection/read failure visible until a new connection attempt
                    // succeeds or returns its own current status.
                }
                else -> {
                    obd2StatusIsError = false
                    obd2Status = "Найдено сопряжённых Bluetooth-устройств: " + available.size + ". Поддерживаются адаптеры Classic SPP."
                }
            }
        } catch (error: Exception) {
            obd2Adapters = emptyList()
            obd2StatusIsError = true
            obd2Status = (error.message ?: "Не удалось получить список Bluetooth-устройств.") +
                " Пробуем резервный вариант: позиционирование продолжается по выбранному GPS/радио источнику."
        }
    }

    val obd2PermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            permissions[Manifest.permission.BLUETOOTH_CONNECT] != true) {
            obd2StatusIsError = true
            obd2Status = "Без разрешения «Устройства поблизости» нельзя подключиться к Bluetooth-адаптеру. Пробуем резервный вариант: позиционирование продолжается по выбранному GPS/радио источнику."
        } else {
            scope.launch { loadPairedObdAdapters() }
        }
    }

    val refreshPairedObdAdapters: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            obd2PermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            scope.launch { loadPairedObdAdapters() }
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == AppTab.OBD2) refreshPairedObdAdapters()
    }

    LaunchedEffect(obd2Connected) {
        if (obd2Connected) {
            while (true) {
                try {
                    val latest = obd2Repository.readTelemetry()
                    obd2Telemetry = latest
                    Obd2SharedState.storeTelemetry(prefs, latest)
                    when {
                        latest.vehicleSpeedKmh != null -> {
                            obd2StatusIsError = false
                            obd2Status = "Скорость получена по стандартному PID 0D. OBD-II используется только как вспомогательный источник; GPS/радио сохраняет приоритет выбранного режима."
                        }
                        latest.speedIssue == Obd2SpeedIssue.PID_UNSUPPORTED -> {
                            obd2StatusIsError = true
                            obd2Status = "ЭБУ сообщил, что PID 0D не поддерживается. Пробуем резервный вариант: продолжаем позиционирование по GPS/радиобазе без автомобильной скорости; неподдерживаемый PID не опрашиваем."
                        }
                        else -> {
                            obd2StatusIsError = true
                            obd2Status = "Не удалось получить корректную скорость через PID 0D. Пробуем резервный вариант: позиционирование продолжается по выбранному GPS/радио источнику без старой скорости. Повторяем безопасное чтение; недостоверное значение отброшено."
                        }
                    }
                    delay(if (latest.speedIssue == Obd2SpeedIssue.PID_UNSUPPORTED) 5_000L else 1_500L)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!obd2Connected) break
                    obd2StatusIsError = true
                    obd2Status = (error.message ?: "Связь с OBD-II адаптером потеряна.") +
                        " Пробуем резервный вариант: GPS/радиопозиционирование продолжается без автомобильной скорости. Старое значение очищено; переподключи адаптер, чтобы возобновить чтение."
                    obd2Connected = false
                    obd2Telemetry = Obd2Telemetry()
                    Obd2SharedState.clearSpeed(prefs)
                    obd2Repository.disconnect()
                    break
                }
            }
        }
    }

    DisposableEffect(obd2Repository) {
        onDispose { obd2Repository.close() }
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
        if (positionMode.usesRadio || positionMode.gpsOnly) liveLocation = null
        if (positionMode.gpsOnly) {
            // Migrate older persisted settings: GPS-only mode must not keep a hidden
            // radio-recording switch armed for when the user later changes modes.
            prefs.edit().putBoolean(ClearGpsPreferences.KEY_RECORDING, false).apply()
            if (recording) recording = false
        }
    }

    LaunchedEffect(obd2Connected) {
        // Keep OBD-II sample age up to date while connected; after disconnect, let the last
        // timestamped speed expire instead of leaving the UI on a stale prediction.
        while (obd2Connected || Obd2SharedState.freshSpeedKmh(prefs, fusionNowMillis) != null) {
            fusionNowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
        fusionNowMillis = System.currentTimeMillis()
    }

    DisposableEffect(positionMode, hasAnyLocationPermission(context), scanIntervalSeconds) {
        if (!hasAnyLocationPermission(context) || positionMode.usesRadio) {
            onDispose { }
        } else {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (isMockLocation(location)) return
                    if (positionMode.gpsOnly && !location.provider.equals(LocationManager.GPS_PROVIDER, true)) return
                    val old = liveLocation
                    if (old == null || isBetterLocation(location, old)) liveLocation = Location(location)
                }
            }
            try {
                val providers = if (positionMode.gpsOnly) listOf(LocationManager.GPS_PROVIDER)
                    else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                providers.forEach { provider ->
                    try {
                        val cached = manager.getLastKnownLocation(provider)
                        if (cached != null && !isMockLocation(cached) &&
                            (!positionMode.gpsOnly || provider == LocationManager.GPS_PROVIDER) &&
                            (liveLocation == null || isBetterLocation(cached, liveLocation!!))) liveLocation = Location(cached)
                        if (manager.allProviders.contains(provider) && manager.isProviderEnabled(provider)) {
                            manager.requestLocationUpdates(provider, scanIntervalSeconds.coerceIn(1, 30) * 1_000L, 0f, listener, Looper.getMainLooper())
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

    LaunchedEffect(recording, appVisible, hasAnyLocationPermission(context), positionMode, scanIntervalSeconds, adaptiveScan, monitorEnabled) {
        // GPS-only mode never runs the radio collection loop. In radio mode the service
        // performs the scan and honours the recording preference when monitoring is active.
        if (recording && !positionMode.gpsOnly && appVisible && hasAnyLocationPermission(context) &&
            !(positionMode.usesRadio && monitorEnabled)) {
            while (recording && appVisible && !positionMode.gpsOnly) {
                performScan(true)
                val obdSpeedKmh = Obd2SharedState.freshSpeedKmh(prefs)?.toFloat()
                val locationSpeedKmh = if (positionMode.usesRadio) null else liveLocation?.takeIf {
                    System.currentTimeMillis() - it.time in 0..30_000L && it.hasSpeed()
                }?.speed?.times(3.6f)
                val speedKmh = obdSpeedKmh ?: locationSpeedKmh
                delay(effectiveScanIntervalSeconds(scanIntervalSeconds, speedKmh, adaptiveScan) * 1_000L)
            }
        }
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = mutableListOf<String>()

        // Ask for precise and approximate location together so Android can offer
        // the precise/approximate choice on supported versions.
        val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted || !coarseGranted) {
            permissionsToRequest += Manifest.permission.ACCESS_FINE_LOCATION
            permissionsToRequest += Manifest.permission.ACCESS_COARSE_LOCATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest += Manifest.permission.BLUETOOTH_SCAN
            }
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest += Manifest.permission.BLUETOOTH_CONNECT
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest += Manifest.permission.POST_NOTIFICATIONS
        }

        if (permissionsToRequest.isNotEmpty()) {
            startupPermissionLauncher.launch(permissionsToRequest.distinct().toTypedArray())
        }
    }

    LaunchedEffect(Unit) {
        refreshLocalUi()
        while (true) {
            monitorEnabled = prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)
            mockEnabled = prefs.getBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)
            mockOutputActive = prefs.getBoolean(LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE, false)
            val outputCoordinate = readPublishedMockCoordinate(prefs)
            if (publishedMockCoordinate != outputCoordinate) publishedMockCoordinate = outputCoordinate
            val outputAccuracy = readPublishedMockAccuracy(prefs)
            if (publishedMockAccuracy != outputAccuracy) publishedMockAccuracy = outputAccuracy
            filterState = prefs.getString(LocationMonitorService.KEY_STATE, FilterState.WAITING.name) ?: FilterState.WAITING.name
            riskScore = prefs.getInt(LocationMonitorService.KEY_SCORE, 0)
            riskReasons = (prefs.getString(LocationMonitorService.KEY_REASONS, "") ?: "").split('\n').filter { it.isNotBlank() }
            riskBreakdown = RiskBreakdownStore.read(prefs, SystemClock.elapsedRealtimeNanos())
            serviceStatus = prefs.getString(LocationMonitorService.KEY_SERVICE_STATUS, "") ?: ""
            fusedMockStatus = prefs.getString(LocationMonitorService.KEY_FUSED_MOCK_STATUS, "") ?: ""
            gnssEnvironment = prefs.getString(LocationMonitorService.KEY_GNSS_ENVIRONMENT, "UNKNOWN") ?: "UNKNOWN"
            gnssEnvironmentReasons = (prefs.getString(LocationMonitorService.KEY_GNSS_ENVIRONMENT_REASONS, "") ?: "").split('\n').filter { it.isNotBlank() }
            gnssBaselineReady = prefs.getBoolean(LocationMonitorService.KEY_GNSS_BASELINE_READY, false)
            gnssBaselineDeviations = (prefs.getString(LocationMonitorService.KEY_GNSS_BASELINE_DEVIATIONS, "") ?: "").split('\n').filter { it.isNotBlank() }
            gnssDiagnosticsAtNanos = prefs.getLong(LocationMonitorService.KEY_GNSS_DIAGNOSTICS_AT_NANOS, 0L)
            gnssDiagnosticsNowNanos = SystemClock.elapsedRealtimeNanos()
            gnssLiveSnapshot = GnssDiagnosticsLiveStore.snapshot(gnssDiagnosticsNowNanos)

            // The foreground service owns radio scans while monitoring is active. Mirror its
            // latest persisted counters/message so the screen cannot stay stuck on an older scan.
            val storedScanAt = if (positionMode.gpsOnly) 0L
                else prefs.getLong(ClearGpsPreferences.KEY_LAST_SCAN_AT, 0L)
            if (!positionMode.gpsOnly && !scanBusy && storedScanAt > 0L && storedScanAt > lastScanAt) {
                wifiCount = prefs.getInt(ClearGpsPreferences.KEY_LAST_WIFI_COUNT, 0)
                cellCount = prefs.getInt(ClearGpsPreferences.KEY_LAST_CELL_COUNT, 0)
                bleCount = prefs.getInt(ClearGpsPreferences.KEY_LAST_BLE_COUNT, 0)
                recordedCount = prefs.getInt(ClearGpsPreferences.KEY_LAST_RECORDED_COUNT, 0)
                lastScanAt = storedScanAt
                scanMessage = prefs.getString(ClearGpsPreferences.KEY_LAST_SCAN_MESSAGE, scanMessage) ?: scanMessage
                val retainedEstimateStillCredible = positionMode.usesRadio && currentRadioEstimate?.let {
                    RadioFixReliability.canRetainLastEstimate(
                        it.updatedAt, System.currentTimeMillis(), it.matchedCount, it.accuracyMeters
                    )
                } == true
                lookupPhase = when {
                    prefs.getBoolean(ClearGpsPreferences.KEY_LAST_SCAN_HAS_ESTIMATE, false) -> LookupPhase.UPDATED
                    retainedEstimateStillCredible -> LookupPhase.STALE
                    wifiCount + cellCount + bleCount == 0 -> LookupPhase.ERROR
                    else -> LookupPhase.NO_MATCH
                }
            }
            if (positionMode.usesRadio) {
                // Keep a short-lived last-known estimate through a single failed scan, but never
                // show it indefinitely as a current position when monitoring is off or matches stop.
                val stored = RadioGeolocationRepository.loadLastEstimate(context)?.takeIf {
                    RadioFixReliability.canDisplayLastRadioEstimateOnMap(
                        it.updatedAt, System.currentTimeMillis(), it.matchedCount, it.accuracyMeters
                    )
                }
                if (stored != currentRadioEstimate) currentRadioEstimate = stored
                if (stored != null && lookupPhase in setOf(LookupPhase.NO_MATCH, LookupPhase.ERROR)) {
                    lookupPhase = LookupPhase.STALE
                }
            }
            delay(1_500L)
        }
    }

    val freshObdSpeedKmh = Obd2SharedState.freshSpeedKmh(prefs, fusionNowMillis)
    val maxLiveAgeMillis = if (freshObdSpeedKmh != null) 30_000L else 15 * 60 * 1_000L
    val validLive = liveLocation?.takeIf { System.currentTimeMillis() - it.time in 0..maxLiveAgeMillis }
    val activeLocation = when (positionMode) {
        PositionMode.GPS_ONLY ->
            validLive?.takeIf { it.provider.equals(LocationManager.GPS_PROVIDER, true) }
        PositionMode.AUTO -> validLive
        PositionMode.RADIO_ONLY -> null
    }
    val measuredCoordinate = activeLocation?.let { MapCoordinate(it.latitude, it.longitude) }
    val estimatedRadioCoordinate = currentRadioEstimate?.let { MapCoordinate(it.latitude, it.longitude) }
    // Prefer the service's exact published fix when available; otherwise show the latest
    // local estimate (including its bounded stale fallback) on the map.
    val radioCoordinate = if (positionMode.usesRadio && mockOutputActive) {
        publishedMockCoordinate ?: estimatedRadioCoordinate
    } else {
        estimatedRadioCoordinate
    }
    val radioAccuracy = if (positionMode.usesRadio && mockOutputActive && publishedMockCoordinate != null) {
        publishedMockAccuracy ?: currentRadioEstimate?.accuracyMeters
    } else {
        currentRadioEstimate?.accuracyMeters
    }
    val obdSpeedUpdatedAt = prefs.getLong(Obd2SharedState.KEY_SPEED_AT, 0L)
    val predictedGpsPosition = if (
        activeLocation != null &&
        activeLocation.provider.equals(LocationManager.GPS_PROVIDER, true) &&
        freshObdSpeedKmh != null
    ) {
        Obd2LocationFusion.predictFromGps(
            lastFix = MapCoordinate(activeLocation.latitude, activeLocation.longitude),
            baseAccuracyMeters = activeLocation.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
            bearingDegrees = activeLocation.takeIf { it.hasBearing() }?.bearing?.toDouble(),
            fixTimeMillis = activeLocation.time,
            speedKmh = freshObdSpeedKmh,
            speedUpdatedAtMillis = obdSpeedUpdatedAt,
            nowMillis = fusionNowMillis
        )
    } else null
    val primaryCoordinate = when (positionMode) {
        PositionMode.GPS_ONLY -> predictedGpsPosition?.coordinate ?: measuredCoordinate
        PositionMode.AUTO -> predictedGpsPosition?.coordinate ?: measuredCoordinate ?: radioCoordinate
        PositionMode.RADIO_ONLY -> radioCoordinate
    }
    val primaryAccuracy = when {
        predictedGpsPosition != null -> predictedGpsPosition.accuracyMeters
        activeLocation?.hasAccuracy() == true -> activeLocation.accuracy.toDouble()
        radioCoordinate != null && (positionMode.usesRadio ||
            (positionMode == PositionMode.AUTO && measuredCoordinate == null)) -> radioAccuracy
        else -> null
    }
    val displayCoordinate = when {
        mockOutputActive && publishedMockCoordinate != null -> publishedMockCoordinate
        positionMode.usesRadio -> radioCoordinate
        else -> primaryCoordinate ?: manualAnchor?.let { MapCoordinate(it.latitude, it.longitude) }
    }
    val displayAccuracy = when {
        mockOutputActive && publishedMockCoordinate != null -> publishedMockAccuracy ?: primaryAccuracy
        positionMode.usesRadio -> radioAccuracy
        else -> primaryAccuracy
    }
    val radioEstimateAgeMillis = currentRadioEstimate?.let { System.currentTimeMillis() - it.updatedAt } ?: Long.MAX_VALUE
    val radioEstimateStale = publishedMockCoordinate == null && currentRadioEstimate != null &&
        radioEstimateAgeMillis > maxOf(15_000L, scanIntervalSeconds * 2_000L)

    val primarySource = when {
        mockOutputActive && publishedMockCoordinate != null &&
            serviceStatus.contains("BeaconDB", ignoreCase = true) ->
            "BeaconDB · временная публикация mock GPS"
        positionMode.usesRadio && publishedMockCoordinate != null ->
            "Mock GPS · опубликованная радиопозиция"
        predictedGpsPosition != null ->
            "GPS + OBD-II · расчёт по скорости (" + predictedGpsPosition.travelledMeters.toInt() + " м)"
        positionMode == PositionMode.RADIO_ONLY && currentRadioEstimate != null && radioEstimateStale ->
            "Последняя оценка радио · свежего совпадения нет"
        positionMode == PositionMode.RADIO_ONLY && currentRadioEstimate != null && freshObdSpeedKmh != null ->
            "Локальная радиобаза + OBD-II"
        positionMode == PositionMode.RADIO_ONLY && currentRadioEstimate != null -> "Локальная радиобаза"
        positionMode == PositionMode.RADIO_ONLY -> "Ожидание совпадений в радиобазе"
        activeLocation?.provider.equals(LocationManager.GPS_PROVIDER, true) && freshObdSpeedKmh != null ->
            "GPS + OBD-II"
        activeLocation?.provider.equals(LocationManager.GPS_PROVIDER, true) -> "GPS"
        positionMode == PositionMode.GPS_ONLY && activeLocation == null && freshObdSpeedKmh != null ->
            "Ожидание GPS · OBD-II не заменяет координаты"
        positionMode == PositionMode.GPS_ONLY && activeLocation == null -> "Ожидание GPS"
        activeLocation != null -> "Сеть Android"
        currentRadioEstimate != null -> "Локальная база"
        manualAnchor != null -> "Ориентир"
        else -> "Нет фикса"
    }
    val mapPrimaryCoordinate = when {
        positionMode.usesRadio -> null
        mockOutputActive && publishedMockCoordinate != null -> publishedMockCoordinate
        else -> predictedGpsPosition?.coordinate ?: measuredCoordinate
    }
    val mapPrimaryAccuracy = when {
        mockOutputActive && publishedMockCoordinate != null -> publishedMockAccuracy ?: primaryAccuracy
        predictedGpsPosition != null -> predictedGpsPosition.accuracyMeters
        activeLocation?.hasAccuracy() == true -> activeLocation.accuracy.toDouble()
        else -> null
    }

    // Update MapLibre only when its actual inputs change. Re-running every map layer update
    // after unrelated UI state changes was rebuilding GeoJSON and causing visible stutter.
    LaunchedEffect(mapIsDark) {
        mapController.setDarkMode(mapIsDark)
    }
    LaunchedEffect(mapSourceMode) {
        mapController.setOfflineMode(mapSourceMode == MapSourceMode.OFFLINE)
    }
    LaunchedEffect(selectedMapPoint != null, showRadioPointsOnMap, positionMode.gpsOnly) {
        // Radio markers are hidden in GPS-only mode so the map does not imply they contribute
        // to the current fix. They remain available in the database and radio modes.
        mapController.setShowRadioPoints(
            !positionMode.gpsOnly && (selectedMapPoint != null || showRadioPointsOnMap)
        )
    }
    LaunchedEffect(mapPrimaryCoordinate, mapPrimaryAccuracy) {
        mapController.updatePrimary(mapPrimaryCoordinate, mapPrimaryAccuracy)
    }
    LaunchedEffect(radioCoordinate, radioAccuracy) {
        mapController.updateCalculatedLocation(radioCoordinate, radioAccuracy)
    }
    LaunchedEffect(positionMode.usesRadio, radioCoordinate, pendingRadioMapFocus) {
        if (positionMode.usesRadio && pendingRadioMapFocus && radioCoordinate != null) {
            mapController.focus(radioCoordinate, 15.0)
            pendingRadioMapFocus = false
        }
    }
    LaunchedEffect(manualAnchor) {
        mapController.updateManualAnchor(manualAnchor)
    }
    LaunchedEffect(zones, draftZone) {
        mapController.updateZones(zones, draftZone)
    }
    val pointsToRender = remember(selectedMapPoint, selectedPointCenter, mapPointsRevision) {
        val detailPoint = selectedMapPoint
        val selectedCenter = selectedPointCenter
        if (detailPoint != null) {
            val lat = selectedCenter?.latitude ?: detailPoint.latitude
            val lon = selectedCenter?.longitude ?: detailPoint.longitude
            if (lat != null && lon != null) listOf(detailPoint.copy(
                latitude = lat, longitude = lon,
                accuracyMeters = selectedCenter?.accuracyMeters ?: detailPoint.accuracyMeters
            )) else emptyList()
        } else mapPoints
    }
    LaunchedEffect(pointsToRender) {
        mapController.updateRadioPoints(pointsToRender)
    }
    LaunchedEffect(mapTapMode) {
        mapController.setTapMode(mapTapMode)
    }
    // Lightweight callback assignments keep handlers fresh without triggering map rendering.
    SideEffect {
        mapController.onRadioPointTap = { pointId -> openRadioPointDetails(pointId) }
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
                    true
                }
                MapTapMode.NONE -> false
            }
        }
    }

    val runMonitoring: () -> Unit = {
        pendingMockEnable = false
        if (!hasAnyLocationPermission(context)) {
            pendingMonitorStart = true
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else startMonitoringWithPermission(enableMock = false)
    }
    val stopMonitoring: () -> Unit = {
        context.startService(Intent(context, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_STOP))
        prefs.edit()
            .putBoolean(LocationMonitorService.KEY_MONITORING, false)
            .putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)
            .apply()
        monitorEnabled = false
        mockEnabled = false
        serviceStatus = "Фикс и мониторинг остановлены"
    }
    val runFix: () -> Unit = {
        pendingMockEnable = true
        if (!hasAnyLocationPermission(context)) {
            pendingMonitorStart = true
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            startMonitoringWithPermission(enableMock = true)
        }
    }
    val stopFix: () -> Unit = {
        prefs.edit()
            .putBoolean(LocationMonitorService.KEY_MONITORING, false)
            .putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)
            .apply()
        context.startService(Intent(context, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_STOP))
        monitorEnabled = false
        mockEnabled = false
        serviceStatus = "Фикс отключён"
        Toast.makeText(context, "Фикс и mock location отключены.", Toast.LENGTH_SHORT).show()
    }
    val enableMock: () -> Unit = {
        pendingMockEnable = true
        if (!hasAnyLocationPermission(context)) {
            pendingMonitorStart = true
            locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else startMonitoringWithPermission(enableMock = true)
    }
    val disableMock: () -> Unit = {
        context.startService(Intent(context, LocationMonitorService::class.java).setAction(LocationMonitorService.ACTION_DISABLE_MOCK))
        prefs.edit().putBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false).apply()
        mockEnabled = false
    }

    BackHandler(enabled = true) {
        when {
            showExportOptions -> showExportOptions = false
            selectedMapPoint != null -> selectedMapPoint = null
            pointToEdit != null -> pointToEdit = null
            showDeleteAllDialog -> showDeleteAllDialog = false
            showRadioOnlyInfo -> showRadioOnlyInfo = false
            showZoneNameDialog -> showZoneNameDialog = false
            zoneToRename != null -> zoneToRename = null
            mapTapMode != MapTapMode.NONE -> { mapTapMode = MapTapMode.NONE; draftZone = emptyList() }
            selectedTab != AppTab.MAP -> { navigateToTab(AppTab.MAP); backExitArmed = false }
            !backExitArmed -> {
                backExitArmed = true
                Toast.makeText(context, "Нажми «Назад» ещё раз в течение 2 секунд, чтобы выйти.", Toast.LENGTH_SHORT).show()
                scope.launch { delay(2_000L); backExitArmed = false }
            }
            else -> (context as? android.app.Activity)?.finish()
        }
    }

    ClearGpsTheme(dark = mapIsDark) {
        val colors = MaterialTheme.colorScheme
        BoxWithConstraints(Modifier.fillMaxSize().background(colors.background)) {
            // Keep the target height available during the exit animation; the visibility
            // transition itself slides the panel off-screen rather than removing it abruptly.
            val panelMaxHeight = maxHeight * panelFraction.value
            val panelBottomOffset = if (panelVisible) panelMaxHeight else 0.dp
            val panelHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
            AndroidView(factory = { viewContext ->
                MapView(viewContext).also { view ->
                    onMapViewCreated(view)
                    mapController.attach(view, viewContext, mapIsDark, mapSourceMode == MapSourceMode.OFFLINE)
                }
            }, modifier = Modifier.fillMaxSize())

            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 7.dp)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeaderCard(tab = selectedTab, onCenter = { mapController.focusPrimary() }, onSettings = { navigateToTab(AppTab.SETTINGS) })
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

                AnimatedVisibility(
                    visible = panelVisible,
                    modifier = Modifier.fillMaxWidth(),
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(190)) +
                        fadeIn(animationSpec = tween(140)),
                    exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(170)) +
                        fadeOut(animationSpec = tween(120))
                ) {
                Surface(
                    Modifier.fillMaxWidth().height(panelMaxHeight),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = colors.surfaceContainer, tonalElevation = 4.dp, shadowElevation = 12.dp
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Box(
                            Modifier.fillMaxWidth().height(28.dp)
                                .pointerInput(panelHeightPx) {
                                    detectVerticalDragGestures { change, dragAmount ->
                                        change.consume()
                                        if (panelHeightPx > 0f) {
                                            val nextFraction = panelFraction.value - dragAmount / panelHeightPx
                                            if (nextFraction < 0.22f) {
                                                panelVisible = false
                                            } else {
                                                scope.launch { panelFraction.snapTo(nextFraction.coerceIn(0.22f, 0.82f)) }
                                            }
                                        }
                                    }
                                }
                                .clickable { panelVisible = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(Modifier.width(40.dp).height(5.dp), shape = CircleShape, color = colors.outlineVariant) {}
                                Icon(
                                    Icons.Outlined.ExpandMore,
                                    contentDescription = "Скрыть меню",
                                    tint = colors.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            when (selectedTab) {
                                AppTab.MAP -> MapHomeScreen(
                                    coordinate = displayCoordinate,
                                    accuracy = if (displayCoordinate != null) displayAccuracy else null,
                                    source = primarySource, mode = positionMode, currentEstimate = currentRadioEstimate,
                                    manualAnchor = manualAnchor, wifiCount = wifiCount, cellCount = cellCount, bleCount = bleCount,
                                    pointCount = pointCount, recording = recording, scanBusy = scanBusy, lookupPhase = lookupPhase,
                                    scanMessage = scanMessage, lastScanAt = lastScanAt, monitorEnabled = monitorEnabled,
                                    mockEnabled = mockEnabled, fixEnabled = monitorEnabled && mockEnabled,
                                    mockOutputActive = mockOutputActive,
                                    fusedMockStatus = fusedMockStatus,
                                    scanIntervalSeconds = scanIntervalSeconds,
                                    filterState = filterState, riskScore = riskScore,
                                    riskReasons = riskReasons, serviceStatus = serviceStatus,
                                    riskBreakdown = riskBreakdown,
                                    gnssEnvironment = gnssEnvironment, gnssEnvironmentReasons = gnssEnvironmentReasons,
                                    gnssBaselineReady = gnssBaselineReady, gnssBaselineDeviations = gnssBaselineDeviations,
                                    gnssDiagnosticsAtNanos = gnssDiagnosticsAtNanos, gnssDiagnosticsNowNanos = gnssDiagnosticsNowNanos,
                                    gnssLiveSnapshot = gnssLiveSnapshot,
                                    onMode = { applyPositionMode(it) },
                                    onFixToggle = { if (monitorEnabled && mockEnabled) stopFix() else runFix() },
                                    onScan = { startScan(recording) },
                                    onManualAnchor = { mapTapMode = if (mapTapMode == MapTapMode.PLACE_ANCHOR) MapTapMode.NONE else MapTapMode.PLACE_ANCHOR },
                                    onRemoveManualAnchor = {
                                        ManualLocationStore.save(context, null)
                                        manualAnchor = null
                                        mapController.updateManualAnchor(null)
                                    },
                                    onRecording = {
                                        recording = it
                                        prefs.edit().putBoolean(ClearGpsPreferences.KEY_RECORDING, it).apply()
                                    },
                                    onMonitoring = { if (monitorEnabled) stopMonitoring() else runMonitoring() },
                                    onMock = { if (mockEnabled) disableMock() else enableMock() },
                                    onOpenPoints = { navigateToTab(AppTab.POINTS) },
                                    onOpenZones = { navigateToTab(AppTab.ZONES) }
                                )
                                AppTab.POINTS -> DatabaseScreen(
                                    points = points, history = history, pointCount = pointCount, observationCount = observationCount,
                                    searchQuery = searchQuery, onSearch = { query ->
                                        searchQuery = query
                                        scope.launch {
                                            points = withContext(Dispatchers.IO) {
                                                if (query.isBlank()) database.listPoints(500) else database.searchPoints(query, 500)
                                            }
                                        }
                                    },
                                    typeFilter = pointTypeFilter,
                                    onTypeFilter = { pointTypeFilter = it },
                                    selectedView = pointsView, onView = { pointsView = it }, onEdit = { openRadioPointDetails(it.id) },
                                    onDelete = {
                                        database.deletePoint(it.id); refreshLocalUi()
                                        Toast.makeText(context, "Точка удалена локально", Toast.LENGTH_SHORT).show()
                                    },
                                    onImport = { importLauncher.launch(arrayOf("*/*")) },
                                    onExport = { showExportOptions = true },
                                    onClear = { showDeleteAllDialog = true },
                                    onRefresh = { refreshLocalUi() },
                                    dataSource = databaseDataSource,
                                    beaconDbEnabled = beaconDbEnabled,
                                    beaconDbLoading = beaconDbLoading,
                                    beaconDbResult = beaconDbLookupResult,
                                    beaconDbMessage = beaconDbMessage,
                                    onDataSource = { source ->
                                        databaseDataSource = source
                                        prefs.edit().putString(ClearGpsPreferences.KEY_DATABASE_VIEW_SOURCE, source.key).apply()
                                        if (source == DatabaseDataSource.LOCAL && beaconDbLookupJob?.isActive == true) {
                                            beaconDbRequestToken += 1
                                            beaconDbLookupJob?.cancel()
                                            beaconDbLookupJob = null
                                            beaconDbLoading = false
                                            beaconDbMessage = "Запрос BeaconDB отменён при возврате к локальной базе."
                                        }
                                    },
                                    onBeaconDbLookup = { startBeaconDbLookup() },
                                    onPublishBeaconDbLocation = { publishBeaconDbEstimateAsMock() },
                                    onOpenBeaconDbSettings = { navigateToTab(AppTab.SETTINGS) }
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
                                AppTab.OBD2 -> Obd2Screen(
                                    adapters = obd2Adapters,
                                    selectedAddress = selectedObd2Address,
                                    connected = obd2Connected,
                                    connecting = obd2Connecting,
                                    telemetry = obd2Telemetry,
                                    status = obd2Status,
                                    statusIsError = obd2StatusIsError,
                                    onRefresh = { refreshPairedObdAdapters() },
                                    onSelect = {
                                        selectedObd2Address = it
                                        prefs.edit().putString(Obd2SharedState.KEY_ADAPTER_ADDRESS, it).apply()
                                    },
                                    onConnect = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                            obd2PermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                                        } else if (selectedObd2Address.isBlank()) {
                                            obd2StatusIsError = true
                                            obd2Status = "Сначала выбери сопряжённый адаптер. Пробуем резервный вариант: позиционирование продолжается по выбранному GPS/радио источнику."
                                        } else {
                                            scope.launch {
                                                obd2Connecting = true
                                                obd2StatusIsError = false
                                                obd2Status = "Подключение к Bluetooth-адаптеру…"
                                                try {
                                                    val adapter = obd2Repository.connect(selectedObd2Address)
                                                    selectedObd2Address = adapter.address
                                                    prefs.edit().putString(Obd2SharedState.KEY_ADAPTER_ADDRESS, adapter.address).apply()
                                                    Obd2SharedState.clearSpeed(prefs)
                                                    obd2Telemetry = Obd2Telemetry()
                                                    obd2Connected = true
                                                    obd2StatusIsError = false
                                                    obd2Status = "Подключено. Проверяю поддержку стандартного PID скорости; остальные автомобильные параметры не запрашиваются."
                                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                    throw cancelled
                                                } catch (error: Exception) {
                                                    obd2Connected = false
                                                    Obd2SharedState.clearSpeed(prefs)
                                                    obd2StatusIsError = true
                                                    obd2Status = (error.message ?: "Не удалось подключиться к адаптеру.") +
                                                        " Пробуем резервный вариант: позиционирование продолжается по выбранному GPS/радио источнику."
                                                } finally {
                                                    obd2Connecting = false
                                                }
                                            }
                                        }
                                    },
                                    onDisconnect = {
                                        obd2Connected = false
                                        obd2Telemetry = Obd2Telemetry()
                                        Obd2SharedState.clearSpeed(prefs)
                                        obd2Repository.disconnect()
                                        obd2StatusIsError = false
                                        obd2Status = "Отключено пользователем. GPS/радиопозиционирование продолжает работать без скорости OBD-II."
                                    },
                                    onOpenBluetoothSettings = {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                        } catch (_: Exception) {
                                            obd2StatusIsError = true
                                            obd2Status = "Не удалось открыть системные настройки Bluetooth. Пробуем резервный вариант: позиционирование продолжается по выбранному GPS/радио источнику."
                                        }
                                    }
                                )
                                AppTab.SETTINGS -> SettingsScreen(
                                    mode = positionMode, mapTheme = mapThemeMode, useWifi = useWifi, useCells = useCells,
                                    useBle = useBle, showRadioPoints = showRadioPointsOnMap,
                                    manualFallbackEnabled = manualFallbackEnabled,
                                    mapSource = mapSourceMode, scanIntervalSeconds = scanIntervalSeconds, adaptiveScan = adaptiveScan,
                                    databaseFacts = "Точек: " + pointCount + " · наблюдений: " + observationCount,
                                    detailedLogging = detailedLogging,
                                    diagnosticLogMaxKb = diagnosticLogMaxKb,
                                    beaconDbEnabled = beaconDbEnabled,
                                    onBeaconDbEnabled = { enabled ->
                                        prefs.edit().putBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, enabled).apply()
                                        beaconDbEnabled = enabled
                                        beaconDbRequestToken += 1
                                        beaconDbLookupJob?.cancel()
                                        beaconDbLookupJob = null
                                        beaconDbLoading = false
                                        beaconDbLookupResult = null
                                        beaconDbMessage = if (enabled) {
                                            "Разрешено. Запросы выполняются только вручную из вкладки «База»."
                                        } else {
                                            "BeaconDB выключён. Новые запросы не отправляются; результат очищен с экрана."
                                        }
                                    },
                                    onDetailedLogging = { enabled ->
                                        DiagnosticLogStore.setEnabled(context, enabled)
                                        detailedLogging = enabled
                                    },
                                    onDiagnosticLogMaxKb = { requestedKb ->
                                        val normalized = (((requestedKb + 32) / 64) * 64).coerceIn(
                                            DiagnosticLogStore.MIN_MAX_KB, DiagnosticLogStore.MAX_MAX_KB
                                        )
                                        DiagnosticLogStore.setMaxKb(context, normalized)
                                        diagnosticLogMaxKb = normalized
                                    },
                                    onViewDiagnosticLogs = {
                                        diagnosticLogText = DiagnosticLogStore.read(context)
                                        showDiagnosticLogs = true
                                    },
                                    onClearDiagnosticLogs = {
                                        DiagnosticLogStore.clear(context)
                                        diagnosticLogText = DiagnosticLogStore.read(context)
                                    },
                                    onMode = { applyPositionMode(it) },
                                    onMapTheme = {
                                        mapThemeMode = it
                                        prefs.edit().putString(ClearGpsPreferences.KEY_MAP_STYLE, it.key).apply()
                                    },
                                    onMapSource = {
                                        mapSourceMode = it
                                        prefs.edit().putString(ClearGpsPreferences.KEY_MAP_SOURCE, it.key).apply()
                                    },
                                    onScanInterval = {
                                        scanIntervalSeconds = it.coerceIn(1, 30)
                                        prefs.edit().putInt(ClearGpsPreferences.KEY_SCAN_INTERVAL_SECONDS, scanIntervalSeconds).apply()
                                    },
                                    onScanIntervalFinished = {
                                        if (monitorEnabled) {
                                            val intent = Intent(context, LocationMonitorService::class.java)
                                                .setAction(LocationMonitorService.ACTION_START)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                                            else context.startService(intent)
                                        }
                                    },
                                    onAdaptiveScan = {
                                        adaptiveScan = it
                                        prefs.edit().putBoolean(ClearGpsPreferences.KEY_ADAPTIVE_SCAN, it).apply()
                                    },
                                    onWifi = { useWifi = it; saveUseFlags() },
                                    onCells = { useCells = it; saveUseFlags() },
                                    onBle = { useBle = it; saveUseFlags() },
                                    onShowPoints = { showRadioPointsOnMap = it },
                                    onManualFallback = {
                                        manualFallbackEnabled = it
                                        prefs.edit().putBoolean(ClearGpsPreferences.KEY_MANUAL_FALLBACK_ENABLED, it).apply()
                                    },
                                    onManualAnchor = { mapTapMode = MapTapMode.PLACE_ANCHOR },
                                    onClearData = { showDeleteAllDialog = true },
                                    onOpenLocationSettings = {
                                        try { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) { }
                                    }
                                )
                            }
                        }

                        NavigationBar(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp).clip(RoundedCornerShape(28.dp)),
                            windowInsets = WindowInsets.navigationBars,
                            containerColor = colors.surfaceContainerHigh, tonalElevation = 3.dp
                        ) {
                            NavigationBarItem(selected = selectedTab == AppTab.MAP, onClick = { navigateToTab(AppTab.MAP) },
                                icon = { Icon(Icons.Outlined.Map, null) }, label = { Text("Главная", maxLines = 1) })
                            NavigationBarItem(selected = selectedTab == AppTab.POINTS, onClick = { navigateToTab(AppTab.POINTS) },
                                icon = { Icon(Icons.Outlined.Storage, null) }, label = { Text("База", maxLines = 1) })
                            NavigationBarItem(selected = selectedTab == AppTab.ZONES, onClick = { navigateToTab(AppTab.ZONES) },
                                icon = { Icon(Icons.Outlined.Shield, null) }, label = { Text("Зоны", maxLines = 1) })
                            NavigationBarItem(selected = selectedTab == AppTab.OBD2, onClick = { navigateToTab(AppTab.OBD2) },
                                icon = { Icon(Icons.Outlined.DirectionsCar, null) }, label = { Text("OBD-II", maxLines = 1) })
                            NavigationBarItem(selected = selectedTab == AppTab.SETTINGS, onClick = { navigateToTab(AppTab.SETTINGS) },
                                icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text("Настройки", maxLines = 1) })
                        }
                    }
                }
                }
            }

            AnimatedVisibility(
                visible = !panelVisible,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp),
                enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(220)) +
                    fadeIn(animationSpec = tween(160)),
                exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(180)) +
                    fadeOut(animationSpec = tween(120))
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = colors.surfaceContainerHigh,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.clickable { panelVisible = true }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Outlined.ExpandLess, contentDescription = "Открыть меню")
                        Text("Открыть меню", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Surface(
                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp,
                    bottom = panelBottomOffset + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp),
                shape = CircleShape, color = colors.primaryContainer, shadowElevation = 6.dp
            ) {
                IconButton(onClick = { mapController.focusPrimary() }, modifier = Modifier.size(50.dp)) {
                    Icon(Icons.Outlined.MyLocation, "Центрировать карту", tint = colors.onPrimaryContainer)
                }
            }
            if (mapSourceMode == MapSourceMode.OFFLINE) {
                Surface(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = panelBottomOffset + 14.dp),
                    shape = RoundedCornerShape(10.dp), color = colors.surfaceContainerHigh.copy(alpha = 0.94f)) {
                    Text("Офлайн-холст · тайлы не запрашиваются", Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurface)
                }
            } else {
                MapAttribution(
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = panelBottomOffset + 14.dp),
                    dark = mapIsDark,
                    onClick = {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) } catch (_: Exception) { }
                    }
                )
            }
        }
    }

    ClearGpsTheme(dark = mapIsDark) {
        if (selectedMapPoint != null) {
        RadioPointDetailsDialog(
            point = selectedMapPoint!!,
            observations = selectedPointObservations,
            center = selectedPointCenter,
            onDismiss = { closeRadioPointDetails() },
            onEdit = {
                pointToEdit = selectedMapPoint
                closeRadioPointDetails()
            }
        )
    }

    if (pointToEdit != null) {
        PointEditDialog(pointToEdit!!, onDismiss = { pointToEdit = null }, onSave = { label, ssid, notes, lat, lon, accuracy ->
            val saved = database.updatePoint(pointToEdit!!.id, label, ssid, notes, lat, lon, accuracy)
            pointToEdit = null; refreshLocalUi()
            Toast.makeText(context, if (saved) "Точка сохранена локально" else "Проверь координаты", Toast.LENGTH_SHORT).show()
        })
    }

    if (showZoneNameDialog) {
        AlertDialog(
            onDismissRequest = { showZoneNameDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Сохранить зону спуфинга") },
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
            onDismissRequest = { zoneToRename = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Переименовать зону") },
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

    if (showExportOptions) {
        AlertDialog(
            onDismissRequest = { showExportOptions = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Экспорт данных") },
            text = {
                Text(
                    "CSV — наблюдения для совместимости с WiGLE. " +
                        "WiGLE SQLite — таблицы network/location для совместимых инструментов. " +
                        "Clear GPS backup — полная копия со всеми точками, наблюдениями и историей сканирований."
                )
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        onClick = {
                            showExportOptions = false
                            exportCsvLauncher.launch("ClearGPS-WiGLE.csv")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("WiGLE CSV") }
                    TextButton(
                        onClick = {
                            showExportOptions = false
                            exportWigleSqliteLauncher.launch("ClearGPS-WiGLE.sqlite")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("WiGLE SQLite") }
                    TextButton(
                        onClick = {
                            showExportOptions = false
                            exportSqliteLauncher.launch("ClearGPS-backup.sqlite")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Резервная копия Clear GPS") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportOptions = false }) { Text("Отмена") }
            }
        )
    }

    if (showDiagnosticLogs) {
        AlertDialog(
            onDismissRequest = { showDiagnosticLogs = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Журнал диагностики Clear GPS") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())
                ) {
                    Text(
                        diagnosticLogText.ifBlank { "Журнал пуст." },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val latestLog = DiagnosticLogStore.read(context)
                    diagnosticLogText = latestLog
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard == null) {
                        Toast.makeText(context, "Буфер обмена недоступен.", Toast.LENGTH_SHORT).show()
                    } else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Clear GPS diagnostic log", latestLog))
                        Toast.makeText(context, "Весь актуальный журнал скопирован.", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Outlined.ContentCopy, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Скопировать всё")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiagnosticLogs = false }) { Text("Закрыть") }
            }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text("Удалить локальную базу?") },
            text = { Text("Будут удалены все сохранённые радио-точки и история сканирований. Зоны риска и ручной ориентир останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    database.clearAll(); currentRadioEstimate = null
                    prefs.edit().remove(ClearGpsPreferences.KEY_LAST_LAT).remove(ClearGpsPreferences.KEY_LAST_LON)
                        .remove(ClearGpsPreferences.KEY_LAST_ACCURACY).remove(ClearGpsPreferences.KEY_LAST_ESTIMATE_AT)
                        .remove(ClearGpsPreferences.KEY_LAST_FINGERPRINT).remove(ClearGpsPreferences.KEY_LAST_MATCHED_COUNT)
                        .remove(ClearGpsPreferences.KEY_LAST_WIFI_COUNT).remove(ClearGpsPreferences.KEY_LAST_CELL_COUNT)
                        .remove(ClearGpsPreferences.KEY_LAST_BLE_COUNT).remove(ClearGpsPreferences.KEY_LAST_SCAN_AT)
                        .remove(ClearGpsPreferences.KEY_LAST_SCAN_MESSAGE)
                        .remove(ClearGpsPreferences.KEY_LAST_SCAN_HAS_ESTIMATE)
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
            onDismissRequest = { showRadioOnlyInfo = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            icon = { Icon(Icons.Outlined.Info, null) },
            title = { Text("Режим «Только радио»") },
            text = {
                Text("Clear GPS не будет запрашивать GPS-провайдер и будет определять положение только по совпадениям с точками из локальной базы. Сначала собери базу в местах с известными координатами или импортируй WiGLE CSV. Без совпадений координат не будет. Обычное приложение Android не может физически выключить GPS-модуль для всей системы; для этого используй системные настройки. Android также может ограничивать Wi‑Fi/Bluetooth-сканирование, когда глобальная геолокация выключена.")
            },
            confirmButton = { TextButton(onClick = { showRadioOnlyInfo = false }) { Text("Понятно") } }
        )
        }
    }
}

@Composable
private fun MapHomeScreen(
    coordinate: MapCoordinate?, accuracy: Double?, source: String, mode: PositionMode,
    currentEstimate: RadioEstimate?, manualAnchor: ManualAnchor?, wifiCount: Int, cellCount: Int, bleCount: Int,
    pointCount: Int, recording: Boolean, scanBusy: Boolean, lookupPhase: LookupPhase, scanMessage: String,
    lastScanAt: Long, monitorEnabled: Boolean, mockEnabled: Boolean, fixEnabled: Boolean,
    mockOutputActive: Boolean, fusedMockStatus: String, scanIntervalSeconds: Int,
    filterState: String, riskScore: Int,
    riskReasons: List<String>, serviceStatus: String,
    riskBreakdown: org.cleargps.core.RiskBreakdown?,
    gnssEnvironment: String, gnssEnvironmentReasons: List<String>,
    gnssBaselineReady: Boolean, gnssBaselineDeviations: List<String>,
    gnssDiagnosticsAtNanos: Long, gnssDiagnosticsNowNanos: Long,
    gnssLiveSnapshot: GnssDiagnosticsLiveSnapshot?,
    onMode: (PositionMode) -> Unit, onFixToggle: () -> Unit, onScan: () -> Unit, onManualAnchor: () -> Unit,
    onRemoveManualAnchor: () -> Unit,
    onRecording: (Boolean) -> Unit, onMonitoring: () -> Unit, onMock: () -> Unit, onOpenPoints: () -> Unit, onOpenZones: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var showDetails by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = colors.surfaceContainerHigh
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Текущее положение",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    StatusPill(
                        when {
                            fixEnabled && mockOutputActive &&
                                serviceStatus.contains("Mock GPS + network published", ignoreCase = true) -> "Mock GPS + Network"
                            fixEnabled && mockOutputActive -> "Mock GPS активен"
                            fixEnabled -> "Фикс ждёт координаты"
                            coordinate != null -> "Координаты найдены"
                            else -> "Нет фикса"
                        },
                        if ((fixEnabled && mockOutputActive) || coordinate != null) colors.primaryContainer else colors.surfaceVariant,
                        if ((fixEnabled && mockOutputActive) || coordinate != null) colors.onPrimaryContainer else colors.onSurfaceVariant
                    )
                }
                if (coordinate != null) {
                    Text(
                        String.format(Locale.US, "%.5f, %.5f", coordinate.latitude, coordinate.longitude),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        (if (accuracy != null) "Точность около ±" + accuracy.toInt() + " м" else "Точность неизвестна") + " · " + source,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (mode.usesRadio && currentEstimate != null) {
                        Text(
                            "Совпадений в базе: " + currentEstimate.matchedCount + ". Радиус — эвристическая оценка.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        when (mode) {
                            PositionMode.RADIO_ONLY -> "Нет совпадений в локальной базе"
                            PositionMode.GPS_ONLY -> "Ожидание GPS-фикса"
                            PositionMode.AUTO -> "Пока нет координат"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        when {
                            mode.usesRadio && pointCount > 0 ->
                                "В базе $pointCount радиоточек, но последний скан не подтвердил положение. Нужны актуальные сигналы и координатированные совпадения; Wi‑Fi может ограничивать Android."
                            mode.usesRadio ->
                                "Сначала запиши радиоточки в известных местах или импортируй локальный CSV с координатами."
                            else -> if (mode.gpsOnly) {
                                "Разреши геолокацию и включи фикс. Радиосканирование в режиме GPS отключено."
                            } else {
                                "Разреши геолокацию и включи фикс. Разовый поиск доступен в диагностике."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }

        Text("Источник положения", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            PositionMode.values().forEach { item ->
                AssistChip(
                    onClick = { if (mode != item) onMode(item) },
                    label = { Text(item.title) },
                    leadingIcon = {
                        if (mode == item) Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(16.dp))
                    }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = onFixToggle,
                modifier = Modifier.weight(1.15f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(if (fixEnabled) Icons.Outlined.CheckCircle else Icons.Outlined.GpsFixed, null)
                Spacer(Modifier.width(7.dp))
                Text(if (fixEnabled) "Отключить фикс" else "Включить фикс", maxLines = 1)
            }
            OutlinedButton(
                onClick = onManualAnchor,
                modifier = Modifier.weight(0.85f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Outlined.EditLocationAlt, null)
                Spacer(Modifier.width(5.dp))
                Text("Ориентир", maxLines = 1)
            }
            if (manualAnchor != null) {
                IconButton(onClick = onRemoveManualAnchor, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Убрать ручной ориентир", tint = colors.error)
                }
            }
        }
        Text(
            when {
                !fixEnabled -> "Фикс выключен · координаты не передаются другим приложениям"
                mockOutputActive && serviceStatus.contains("holding last", ignoreCase = true) ->
                    "Android принял mock-координаты · временно удерживается последняя радиопозиция с растущей погрешностью"
                mockOutputActive -> "Android принял mock-обновление через " +
                    (if (serviceStatus.contains("Mock GPS + network published", ignoreCase = true)) "GPS + Network" else "GPS") +
                    " · целевой интервал $scanIntervalSeconds с"
                else -> "Фикс включён, но mock GPS сейчас не публикуется"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (fixEnabled && !mockOutputActive) colors.error else if (fixEnabled) colors.primary else colors.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (fixEnabled && fusedMockStatus.isNotBlank()) {
            val fusedStatusIsWarning = listOf("не принял", "недоступен", "не удалось", "ошибка", "проверь")
                .any { fusedMockStatus.contains(it, ignoreCase = true) }
            Text(
                "Fused Location: " + fusedMockStatus,
                style = MaterialTheme.typography.bodySmall,
                color = if (fusedStatusIsWarning) colors.error else colors.onSurfaceVariant
            )
        }
        if (fixEnabled && !mockOutputActive) {
            Text(
                "Другие приложения сейчас не получают новые mock-координаты. Статус: " +
                    serviceStatus.ifBlank { "ожидание первого надёжного положения" } +
                    ". Проверь выбор Clear GPS в настройках разработчика и доступность совпадений.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.error
            )
        } else if (fixEnabled && mockOutputActive) {
            Text(
                "Android принимает тестовую позицию. Координаты помечены как mock; приложения, блокирующие фиктивную геолокацию, могут их игнорировать.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
        if (!mode.gpsOnly) {
            if (scanBusy) LinearProgressIndicator(Modifier.fillMaxWidth())

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceChip(Icons.Outlined.Wifi, "Wi‑Fi $wifiCount", wifiCount > 0)
                SourceChip(Icons.Outlined.SignalCellularAlt, "Соты $cellCount", cellCount > 0)
                SourceChip(Icons.Outlined.Bluetooth, "BLE $bleCount", bleCount > 0)
            }

            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (lookupPhase == LookupPhase.ERROR) colors.errorContainer else colors.surfaceContainer
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            when (lookupPhase) {
                                LookupPhase.UPDATED -> Icons.Outlined.CheckCircle
                                LookupPhase.ERROR -> Icons.Outlined.Info
                                else -> Icons.Outlined.Refresh
                            },
                            null,
                            tint = if (lookupPhase == LookupPhase.ERROR) colors.onErrorContainer else colors.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (lookupPhase) {
                                LookupPhase.SCANNING -> "Сканирование"
                                LookupPhase.UPDATED -> "Радиоданные обновлены"
                                LookupPhase.STALE -> "Последняя оценка сохранена"
                                LookupPhase.NO_MATCH -> "Нет совпадения в радиобазе"
                                LookupPhase.ERROR -> "Не удалось завершить сканирование"
                                LookupPhase.IDLE -> "Состояние радиопоиска"
                            },
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (lookupPhase == LookupPhase.ERROR) colors.onErrorContainer else colors.onSurface
                        )
                    }
                    Text(
                        scanMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (lookupPhase == LookupPhase.ERROR) colors.onErrorContainer else colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = if (recording) colors.secondaryContainer else colors.surfaceContainer
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (recording) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked,
                        null,
                        tint = if (recording) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        modifier = Modifier.size(21.dp)
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (recording) "Запись включена" else "Локальная запись",
                            fontWeight = FontWeight.SemiBold,
                            color = if (recording) colors.onSecondaryContainer else colors.onSurface
                        )
                        Text(
                            if (recording) "Сохраняет радионаблюдения на устройстве"
                            else "Ничего не сохраняется без включения",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (recording) colors.onSecondaryContainer else colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(checked = recording, onCheckedChange = onRecording)
                }
            }


        }

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, null, tint = colors.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Фильтр GNSS", fontWeight = FontWeight.SemiBold)
                        Text(
                            when (filterState) {
                                FilterState.WAITING.name -> "Ожидание данных"
                                FilterState.NORMAL.name -> "Явных аномалий не обнаружено"
                                FilterState.SUSPECT.name -> "Есть подозрительные признаки"
                                FilterState.ZONE_RISK.name -> "Позиция в зоне риска"
                                FilterState.CORRECTING.name -> "Используется резервная позиция"
                                FilterState.NO_FALLBACK.name -> "Нет пригодного резерва"
                                else -> filterState
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        riskScore.toString() + "/100",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (riskScore >= 60) colors.error else colors.primary
                    )
                }
                LinearProgressIndicator(
                    progress = { riskScore.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (riskScore >= 60) colors.error else colors.primary,
                    trackColor = colors.surfaceVariant
                )
                Text("Индекс риска — эвристика, не вероятность атаки.", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }

        TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.fillMaxWidth()) {
            Icon(if (showDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            Spacer(Modifier.width(6.dp))
            Text(if (showDetails) "Скрыть диагностику и инструменты" else "Диагностика и дополнительные инструменты")
        }

        if (showDetails) {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (!mode.gpsOnly) {
                    FilledTonalButton(
                        onClick = onScan,
                        enabled = !scanBusy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Outlined.Refresh, null)
                        Spacer(Modifier.width(7.dp))
                        Text(if (scanBusy) "Сканирую…" else "Сканировать сейчас")
                    }
                    if (lastScanAt > 0L) {
                        Text("Последний скан: " + dateTime(lastScanAt), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                } else {
                    Text(
                        "В режиме GPS радиосканирование и обращение к локальной радиобазе отключены. Фильтр анализирует данные GPS/GNSS.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                if (mode.usesRadio) {
                    Text(
                        "В режиме «Только радио» приложение не запрашивает GPS. При подключённом OBD-II свежая скорость может адаптивно учащать сканирование, но без надёжного курса сама по себе не определяет координаты.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                if (manualAnchor != null) {
                    Text(
                        "Ручной ориентир: " + String.format(Locale.US, "%.5f, %.5f", manualAnchor.latitude, manualAnchor.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                RiskBreakdownPanel(
                    breakdown = riskBreakdown,
                    currentRiskScore = riskScore,
                    monitorEnabled = monitorEnabled,
                    radioOnly = mode.usesRadio
                )
                if (riskReasons.isNotEmpty()) {
                    Text(
                        "Причины риска: " + riskReasons.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (riskScore >= 60) colors.error else colors.onSurfaceVariant
                    )
                } else {
                    Text(
                        "Проверяются траектория, скорость, расхождение с сетевой позицией и данные спутников. Отсутствие тревоги не доказывает отсутствие спуфинга.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                val gnssDiagnosticsFresh = gnssDiagnosticsAtNanos > 0L &&
                    gnssDiagnosticsNowNanos >= gnssDiagnosticsAtNanos &&
                    gnssDiagnosticsNowNanos - gnssDiagnosticsAtNanos <= 10_000_000_000L
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surfaceContainer
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Диагностика качества GNSS", fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                mode.usesRadio -> "В режиме «Только радио» GNSS не запрашивается."
                                !monitorEnabled -> "Включи мониторинг в режиме GPS или Авто, чтобы получать свежие GNSS-измерения."
                                !gnssDiagnosticsFresh -> "Свежие GNSS-измерения пока недоступны. Старые данные не показываются как текущие."
                                else -> when (gnssEnvironment) {
                                    "LIKELY_OPEN_SKY" -> "Контекст приёма: хорошие условия по доступным агрегированным метрикам."
                                    "POSSIBLE_PARTIAL_OBSTRUCTION" -> "Контекст приёма: возможно частичное ухудшение сигнала."
                                    "POSSIBLE_SEVERE_OBSTRUCTION" -> "Контекст приёма: сильное ухудшение GNSS-сигнала."
                                    "GNSS_UNAVAILABLE" -> "GNSS недоступен: видимые спутники не обнаружены; причина неизвестна."
                                    else -> "Контекст приёма неизвестен: недостаточно пригодных свежих данных."
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                        if (gnssDiagnosticsFresh && !mode.usesRadio && monitorEnabled) {
                            if (gnssEnvironmentReasons.isNotEmpty()) {
                                Text(
                                    gnssEnvironmentReasons.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Text(
                                when {
                                    !gnssBaselineReady -> "Индивидуальная норма устройства пока не готова: недостаточно принятых чистых измерений."
                                    gnssBaselineDeviations.isNotEmpty() -> "Отклонения от индивидуальной нормы: " + gnssBaselineDeviations.joinToString(" · ")
                                    gnssEnvironment == "UNKNOWN" || gnssEnvironment == "GNSS_UNAVAILABLE" ->
                                        "Недостаточно пригодных измерений, чтобы сравнить приём с индивидуальной нормой."
                                    else -> "В доступных GNSS-измерениях крупных отклонений не выявлено."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (gnssBaselineReady && gnssBaselineDeviations.isNotEmpty()) colors.error else colors.onSurfaceVariant
                            )
                        }
                        Text(
                            "Это диагностические подсказки о качестве приёма, а не подтверждение спуфинга или глушения. Они не меняют индекс риска и публикацию координат.",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                GnssSatelliteDiagnosticsPanel(
                    snapshot = gnssLiveSnapshot,
                    monitorEnabled = monitorEnabled,
                    radioOnly = mode.usesRadio
                )
                Text(
                    serviceStatus.ifBlank { if (monitorEnabled) "Фоновый мониторинг активен." else "Фоновый мониторинг выключен." },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onMonitoring, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Text(if (monitorEnabled) "Остановить мониторинг" else "Мониторинг")
                    }
                    FilledTonalButton(onClick = onMock, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Text(if (mockEnabled) "Выключить mock" else "Включить mock")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = onOpenPoints, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Outlined.Storage, null)
                        Spacer(Modifier.width(6.dp))
                        Text("База · " + pointCount)
                    }
                    OutlinedButton(onClick = onOpenZones, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Outlined.Shield, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Зоны риска")
                    }
                }
                Text(
                    "Радиоидентификаторы остаются на устройстве, кроме явно разрешённого ручного BeaconDB-запроса, который отправляет только ID сотовых ячеек и уровень сигнала. API WiGLE не вызывается. Онлайн-карта OpenFreeMap видит сетевые метаданные обычных запросов тайлов.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DatabaseScreen(
    points: List<RadioPointRecord>, history: List<RadioScanHistoryItem>, pointCount: Int, observationCount: Int,
    searchQuery: String, onSearch: (String) -> Unit,
    typeFilter: RadioPointTypeFilter, onTypeFilter: (RadioPointTypeFilter) -> Unit,
    selectedView: PointsView, onView: (PointsView) -> Unit, onEdit: (RadioPointRecord) -> Unit,
    onDelete: (RadioPointRecord) -> Unit, onImport: () -> Unit, onExport: () -> Unit, onClear: () -> Unit, onRefresh: () -> Unit,
    dataSource: DatabaseDataSource, beaconDbEnabled: Boolean, beaconDbLoading: Boolean,
    beaconDbResult: BeaconDbLookupResult?, beaconDbMessage: String,
    onDataSource: (DatabaseDataSource) -> Unit, onBeaconDbLookup: () -> Unit,
    onPublishBeaconDbLocation: () -> Unit, onOpenBeaconDbSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val filteredPoints = points.filter { point -> typeFilter.type == null || point.type == typeFilter.type }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            DatabaseDataSource.values().forEach { source ->
                val selected = dataSource == source
                AssistChip(
                    onClick = { onDataSource(source) },
                    label = { Text(source.title) },
                    leadingIcon = {
                        Icon(
                            if (source == DatabaseDataSource.LOCAL) Icons.Outlined.Storage
                            else Icons.Outlined.SignalCellularAlt,
                            contentDescription = null
                        )
                    },
                    colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                        containerColor = if (selected) colors.secondaryContainer else colors.surfaceVariant,
                        labelColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                    )
                )
            }
        }
        if (dataSource == DatabaseDataSource.LOCAL) {
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
        if (selectedView == PointsView.POINTS) {
            OutlinedTextField(value = searchQuery, onValueChange = onSearch, singleLine = true,
                modifier = Modifier.fillMaxWidth(), placeholder = { Text("Поиск по SSID, MAC/BSSID или заметкам") },
                label = { Text("Поиск в локальной базе") })
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RadioPointTypeFilter.values().forEach { filter ->
                    val selected = typeFilter == filter
                    AssistChip(
                        onClick = { onTypeFilter(filter) },
                        label = { Text(filter.title) },
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = if (selected) colors.secondaryContainer else colors.surfaceVariant,
                            labelColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                        )
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = onImport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Outlined.ImportExport, null); Spacer(Modifier.width(5.dp)); Text("Импорт CSV / SQLite")
            }
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(15.dp)) {
                Icon(Icons.Outlined.ImportExport, null); Spacer(Modifier.width(5.dp)); Text("Экспорт…")
            }
            IconButton(onClick = onClear) { Icon(Icons.Outlined.DeleteOutline, "Удалить базу") }
        }
        Text(if (selectedView == PointsView.POINTS)
            "SSID и BSSID хранятся только локально. Нажми на точку на карте для просмотра подробностей или редактирования."
        else "Хранятся последние 500 сканирований. История остаётся на устройстве.",
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)

        if (selectedView == PointsView.POINTS) {
            if (filteredPoints.isEmpty()) {
                if (points.isEmpty()) EmptyState("Точек пока нет", "Включи запись на карте или импортируй WiGLE CSV.")
                else EmptyState("Нет точек этого типа", "Выбери «Все» или другой тип: Wi‑Fi, вышки либо Bluetooth.")
            } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(filteredPoints, key = { it.id }) { point ->
                    RadioPointRow(point, onEdit = { onEdit(point) }, onDelete = { onDelete(point) })
                }
            }
        } else {
            if (history.isEmpty()) EmptyState("История пуста", "Первый запуск сканирования добавит сюда запись.")
            else LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
        } else {
            BeaconDbDatabaseContent(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                enabled = beaconDbEnabled,
                loading = beaconDbLoading,
                result = beaconDbResult,
                message = beaconDbMessage,
                onLookup = onBeaconDbLookup,
                onPublishAsMock = onPublishBeaconDbLocation,
                onOpenSettings = onOpenBeaconDbSettings
            )
        }
    }
}

@Composable
private fun BeaconDbDatabaseContent(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    loading: Boolean,
    result: BeaconDbLookupResult?,
    message: String,
    onLookup: () -> Unit,
    onPublishAsMock: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("BeaconDB · только сотовые вышки", fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    "Это не полный каталог всех вышек. Публичного дампа BeaconDB сейчас нет; её API возвращает оценку местоположения по текущим сотовым ячейкам. Данные не импортируются в локальную SQLite-базу.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
        if (!enabled) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Сетевые запросы выключены", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Пока выключено, Clear GPS ничего не отправляет в BeaconDB. Чтобы выполнить поиск, сначала явно разреши cell-only запросы в настройках.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Outlined.Settings, null)
                        Spacer(Modifier.width(7.dp))
                        Text("Настройки BeaconDB")
                    }
                }
            }
        } else {
            FilledTonalButton(
                onClick = onLookup,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Outlined.Refresh, null)
                Spacer(Modifier.width(7.dp))
                Text(if (loading) "Запрашиваю BeaconDB…" else "Получить оценку по текущим сотам")
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            if (result != null) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            if (result.success) "Оценка BeaconDB" else "Результат запроса",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        val coordinate = result.coordinate
                        if (coordinate != null) {
                            Text(
                                String.format(Locale.US, "%.6f, %.6f", coordinate.latitude, coordinate.longitude),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Ориентировочная погрешность: ±" + coordinate.accuracyMeters.toInt() + " м",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                            FilledTonalButton(
                                onClick = onPublishAsMock,
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Icon(Icons.Outlined.GpsFixed, null)
                                Spacer(Modifier.width(7.dp))
                                Text("Передать эту оценку в Mock GPS на 2 минуты")
                            }
                            Text(
                                "Это отдельное действие: приложения получат mock-координату через GPS и Network. " +
                                    "Позиция не обновляется запросами автоматически; погрешность увеличивается, затем исходный режим возобновляется.",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant
                            )
                        } else {
                            Text("Координаты не получены.", style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant)
                        }
                        Text(
                            "Сотовых ячеек в запросе: " + result.cells.size +
                                if (result.unsupportedCellCount > 0) " · неподдерживаемых пропущено: " +
                                    result.unsupportedCellCount else "",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant
                        )
                        if (result.queriedAtMillis > 0L) {
                            Text("Время запроса: " + dateTime(result.queriedAtMillis),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant)
                        }
                    }
                }
                if (result.cells.isNotEmpty()) {
                    Text("Сотовые ячейки, включённые в этот запрос",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Это список сигналов, полученных от Android, а не выгрузка записей BeaconDB.",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(result.cells, key = { it.identifier }) { cell ->
                            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp),
                                color = colors.surfaceContainerHigh) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.SignalCellularAlt, null, tint = colors.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(cell.radioType.uppercase() + " · " + cell.mobileCountryCode +
                                            "/" + cell.mobileNetworkCode, fontWeight = FontWeight.SemiBold)
                                        Text("Зона " + cell.locationAreaCode + " · ID соты " + cell.cellId,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    Text(cell.signalStrength.toString() + " dBm",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.height(1.dp))
                }
            }
        }
    }
}

@Composable
private fun RadioPointRow(point: RadioPointRecord, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val typeLabel = when (point.type) { "wifi" -> "Wi‑Fi"; "cell" -> "Сотовая"; else -> "Bluetooth LE" }
    Surface(onClick = onEdit, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp), color = colors.surfaceContainerHigh) {
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
                Text(point.label.ifBlank { point.ssid.ifBlank { typeLabel + " · " + point.identifier.takeLast(11) } },
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (point.type == "wifi" && point.ssid.isNotBlank()) {
                    Text("SSID: " + point.ssid, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(typeLabel + " · " + point.identifier + " · RSSI " + point.rssi + " dBm · " + point.sightings + " наблюдений",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(if (point.latitude != null && point.longitude != null)
                    String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude)
                else "Координаты не заданы", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (point.notes.isNotBlank()) Text(point.notes, style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = colors.onSurfaceVariant)
            }
            Column {
                IconButton(onClick = onEdit, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.Edit, "Подробности и наблюдения точки") }
                IconButton(onClick = onDelete, modifier = Modifier.size(38.dp)) { Icon(Icons.Outlined.DeleteOutline, "Удалить точку") }
            }
        }
    }
}

@Composable
private fun PointEditDialog(
    point: RadioPointRecord, onDismiss: () -> Unit,
    onSave: (String, String, String, Double?, Double?, Double?) -> Unit
) {
    var label by remember(point.id) { mutableStateOf(point.label) }
    var ssid by remember(point.id) { mutableStateOf(point.ssid) }
    var notes by remember(point.id) { mutableStateOf(point.notes) }
    var lat by remember(point.id) { mutableStateOf(point.latitude?.toString().orEmpty()) }
    var lon by remember(point.id) { mutableStateOf(point.longitude?.toString().orEmpty()) }
    var accuracy by remember(point.id) { mutableStateOf(point.accuracyMeters?.toString().orEmpty()) }
    var error by remember(point.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Редактировать радиоточку") },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        text = {
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(point.identifier, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (point.type == "wifi") {
                    OutlinedTextField(value = ssid, onValueChange = { ssid = it.take(100) },
                        label = { Text("SSID") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
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
                else onSave(label, ssid, notes, latitude, longitude, acc)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun RadioPointDetailsDialog(
    point: RadioPointRecord,
    observations: List<RadioPointObservation>,
    center: RadioPointCenter?,
    onDismiss: () -> Unit,
    onEdit: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val kind = when (point.type) {
        "wifi" -> "Wi‑Fi"
        "cell" -> "Сотовая вышка"
        else -> "Bluetooth LE"
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(10.dp),
            shape = RoundedCornerShape(28.dp),
            color = colors.surfaceContainer,
            tonalElevation = 5.dp
        ) {
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = when (point.type) {
                            "wifi" -> Color(0xFFD7F5E1)
                            "cell" -> Color(0xFFF1DFFF)
                            else -> Color(0xFFFFE8BE)
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                when (point.type) {
                                    "wifi" -> Icons.Outlined.Wifi
                                    "cell" -> Icons.Outlined.SignalCellularAlt
                                    else -> Icons.Outlined.Bluetooth
                                },
                                contentDescription = null,
                                tint = Color(0xFF263248)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            point.ssid.ifBlank { point.label.ifBlank { kind } },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(kind + " · " + point.sightings + " записанных наблюдений",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = "Закрыть подробности")
                    }
                }
                Divider(color = colors.outlineVariant)
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "summary") {
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = colors.surfaceContainerHigh
                        ) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Сведения о точке", style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold)
                                Text("MAC / ID: " + point.identifier, style = MaterialTheme.typography.bodySmall)
                                if (point.type == "wifi") Text("SSID: " + point.ssid.ifBlank { "неизвестен" })
                                Text("Последний сигнал в базе: " + point.rssi + " dBm · максимум: " + point.peakRssi + " dBm")
                                if (point.frequency > 0) Text("Последняя частота: " + point.frequency + " MHz")
                                Text("Источник сохранённой позиции: " + point.positionSource,
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                if (point.notes.isNotBlank()) Text("Заметки: " + point.notes)
                                Divider(color = colors.outlineVariant)
                                if (center != null) {
                                    Text("Примерный центр наблюдений",
                                        fontWeight = FontWeight.SemiBold, color = colors.primary)
                                    Text(String.format(Locale.US, "%.6f, %.6f", center.latitude, center.longitude),
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("По ${center.sampleCount} наблюдениям с координатами · ориентировочная погрешность ±${center.accuracyMeters.toInt()} м",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    Text("Это центр мест, из которых сигнал записывался, а не точная триангуляция передатчика.",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                } else if (point.latitude != null && point.longitude != null) {
                                    Text("Сохранённая координата",
                                        fontWeight = FontWeight.SemiBold, color = colors.primary)
                                    Text(String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude),
                                        style = MaterialTheme.typography.titleMedium)
                                    Text("В истории пока нет координат для расчёта центра.",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    Text("Точность сохранённой позиции: ±${point.accuracyMeters?.toInt()?.toString() ?: "—"} м",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                } else {
                                    Text("Координаты пока неизвестны",
                                        fontWeight = FontWeight.SemiBold, color = colors.primary)
                                    Text("В наблюдениях нет сохранённых координат. Центр появится после записи точки с доступной геопозицией.",
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item(key = "observations-title") {
                        Text("Все наблюдения (${observations.size})",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp))
                        Text("История хранится локально; последние записи идут первыми.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    if (observations.isEmpty()) {
                        item(key = "observations-empty") {
                            EmptyState("Истории пока нет",
                                "У импортированных точек может быть сохранена позиция, но отсутствовать история отдельных наблюдений.")
                        }
                    } else {
                        items(observations, key = { it.id }) { observation ->
                            RadioPointObservationRow(observation)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(15.dp)
                    ) { Text("Закрыть") }
                    FilledTonalButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(15.dp)
                    ) {
                        Icon(Icons.Outlined.Edit, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Редактировать")
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioPointObservationRow(observation: RadioPointObservation) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerHigh
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = colors.primary)
                Spacer(Modifier.width(7.dp))
                Text(dateTime(observation.observedAt), fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                Text("${observation.rssi} dBm", style = MaterialTheme.typography.labelLarge,
                    color = colors.primary, fontWeight = FontWeight.SemiBold)
            }
            if (observation.latitude != null && observation.longitude != null) {
                Text(String.format(Locale.US, "%.6f, %.6f", observation.latitude, observation.longitude),
                    style = MaterialTheme.typography.bodyMedium)
                Text("Точность позиции: ±${observation.accuracyMeters?.toInt()?.toString() ?: "—"} м",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            } else {
                Text("Координаты не были доступны при этом наблюдении",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (observation.frequency > 0) {
                Text("Частота: ${observation.frequency} MHz",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text("Источник позиции: " + observation.positionSource,
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ZonesScreen(
    zones: List<SavedRiskZone>, draftZone: List<ZonePoint>, mapTapMode: MapTapMode,
    onStartDraw: () -> Unit, onSaveDraft: () -> Unit,
    onToggle: (SavedRiskZone, Boolean) -> Unit, onRename: (SavedRiskZone) -> Unit,
    onDelete: (SavedRiskZone) -> Unit, onCancelDraw: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
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
    showRadioPoints: Boolean, manualFallbackEnabled: Boolean, databaseFacts: String,
    detailedLogging: Boolean, diagnosticLogMaxKb: Int,
    beaconDbEnabled: Boolean, onBeaconDbEnabled: (Boolean) -> Unit,
    onDetailedLogging: (Boolean) -> Unit, onDiagnosticLogMaxKb: (Int) -> Unit,
    onViewDiagnosticLogs: () -> Unit, onClearDiagnosticLogs: () -> Unit,
    mapSource: MapSourceMode, scanIntervalSeconds: Int, adaptiveScan: Boolean,
    onMode: (PositionMode) -> Unit, onMapTheme: (MapThemeMode) -> Unit, onMapSource: (MapSourceMode) -> Unit,
    onScanInterval: (Int) -> Unit, onScanIntervalFinished: () -> Unit,
    onAdaptiveScan: (Boolean) -> Unit,
    onWifi: (Boolean) -> Unit, onCells: (Boolean) -> Unit, onBle: (Boolean) -> Unit,
    onShowPoints: (Boolean) -> Unit, onManualFallback: (Boolean) -> Unit, onManualAnchor: () -> Unit,
    onClearData: () -> Unit, onOpenLocationSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var showMapOptions by remember { mutableStateOf(false) }
    var showRadioOptions by remember { mutableStateOf(false) }
    var showAdvancedOptions by remember { mutableStateOf(false) }
    var showDiagnosticOptions by remember { mutableStateOf(false) }
    var pendingDiagnosticLogMaxKb by remember(diagnosticLogMaxKb) { mutableStateOf(diagnosticLogMaxKb.toFloat()) }
    var pendingScanInterval by remember(scanIntervalSeconds) { mutableStateOf(scanIntervalSeconds.toFloat()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text("Настройки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surfaceContainer) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                SettingSwitch(
                    "Разрешить запросы к BeaconDB",
                    "По умолчанию выключено. При включении и ручном поиске передаются только ID текущих поддерживаемых сотовых ячеек и уровни сигнала. IP-адрес виден серверу; Wi-Fi, Bluetooth и GPS-координаты в запрос не входят. Отправка вкладов/наблюдений не используется.",
                    beaconDbEnabled,
                    onBeaconDbEnabled
                )
                Text(
                    "Включение не запускает запросы автоматически. BeaconDB вызывается только кнопкой во вкладке «База».",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Режим положения", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                PositionMode.values().forEach { item ->
                    Row(
                        Modifier.fillMaxWidth().clickable { if (mode != item) onMode(item) }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontWeight = FontWeight.Medium)
                            Text(
                                when (item) {
                                    PositionMode.AUTO -> "Android location с резервом локальной радиобазы; свежая скорость OBD-II учитывается автоматически, когда доступна."
                                    PositionMode.GPS_ONLY -> "GPS — основной источник; при подключённом OBD-II свежая скорость помогает краткому прогнозу по достоверному GPS-курсу."
                                    PositionMode.RADIO_ONLY -> "Локальная радиобаза; OBD-II может адаптировать частоту сканирования, но без курса не определяет координаты. GPS не запрашивается."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        RadioButton(selected = mode == item, onClick = { onMode(item) })
                    }
                }
            }
        }

        SettingsSectionToggle(
            title = "Карта и оформление",
            summary = "Источник карты, тема и приватность тайлов",
            expanded = showMapOptions,
            onClick = { showMapOptions = !showMapOptions }
        )
        if (showMapOptions) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Источник карты", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    MapSourceMode.values().forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onMapSource(item) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.Medium)
                                Text(
                                    if (item == MapSourceMode.ONLINE)
                                        "Дороги и подписи доступны онлайн; сервер видит IP и область карты."
                                    else "Без внешних тайлов: фон пустой, локальные точки остаются видны.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            RadioButton(selected = mapSource == item, onClick = { onMapSource(item) })
                        }
                    }
                    Divider(color = colors.outlineVariant)
                    Text("Тема карты", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    MapThemeMode.values().forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onMapTheme(item) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item.title, modifier = Modifier.weight(1f))
                            RadioButton(selected = mapTheme == item, onClick = { onMapTheme(item) })
                        }
                    }
                }
            }
        }

        SettingsSectionToggle(
            title = "Радиопоиск и частота фикса",
            summary = "Источники радиоданных и интервал 1–30 секунд",
            expanded = showRadioOptions,
            onClick = { showRadioOptions = !showRadioOptions }
        )
        if (showRadioOptions) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(
                        "Переключатели влияют на локальный поиск. Сохранение наблюдений работает только при включённой записи.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    SettingSwitch("Wi‑Fi", "BSSID и уровень сигнала.", useWifi, onWifi)
                    Divider(color = colors.outlineVariant)
                    SettingSwitch("Мобильные соты", "GSM / WCDMA / LTE / NR, если доступны.", useCells, onCells)
                    Divider(color = colors.outlineVariant)
                    SettingSwitch("Bluetooth LE", "Адрес и уровень сигнала; имена устройств не читаются.", useBle, onBle)
                    Divider(color = colors.outlineVariant)
                    Text("Интервал обновления фикса: " + pendingScanInterval.roundToInt() + " с", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = pendingScanInterval,
                        onValueChange = { pendingScanInterval = it },
                        onValueChangeFinished = {
                            val selectedInterval = pendingScanInterval.roundToInt().coerceIn(1, 30)
                            if (selectedInterval != scanIntervalSeconds) {
                                onScanInterval(selectedInterval)
                                onScanIntervalFinished()
                            }
                        },
                        valueRange = 1f..30f,
                        steps = 28,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "Интервал применяется к GPS-обновлениям и повторному радиопоиску. Android может ограничивать фактическую частоту Wi‑Fi/Bluetooth-сканов.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                    SettingSwitch(
                        "Ускорять в движении",
                        "При скорости выше 25 км/ч интервал уменьшается вдвое. Android может ограничить сканы.",
                        adaptiveScan,
                        onAdaptiveScan
                    )
                    Divider(color = colors.outlineVariant)
                    SettingSwitch(
                        "Радиоточки на карте",
                        "Показывать сохранённые точки поверх карты.",
                        showRadioPoints,
                        onShowPoints
                    )
                }
            }
        }

        SettingsSectionToggle(
            title = "Дополнительно и данные",
            summary = "Резервный ориентир, разрешения и локальная база",
            expanded = showAdvancedOptions,
            onClick = { showAdvancedOptions = !showAdvancedOptions }
        )
        if (showAdvancedOptions) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SettingSwitch(
                        "Ручной ориентир как резерв",
                        "Используется только как грубый запасной вариант; оценка может ошибаться на километры.",
                        manualFallbackEnabled,
                        onManualFallback
                    )
                    OutlinedButton(onClick = onManualAnchor, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Outlined.LocationOn, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Поставить ориентир на карте")
                    }
                    OutlinedButton(onClick = onOpenLocationSettings, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Outlined.GpsFixed, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Системные настройки геолокации")
                    }
                    Divider(color = colors.outlineVariant)
                    Text("Приватность", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (beaconDbEnabled) {
                            "BeaconDB разрешён только для ручных cell-only запросов из вкладки «База»: сервер получает ID текущих сотовых ячеек и уровни сигнала, а также видит IP-адрес. Wi-Fi, Bluetooth, GPS-координаты и вклад в базу не отправляются. API WiGLE не вызывается."
                        } else {
                            "Запросы BeaconDB выключены. Радиоидентификаторы остаются на устройстве; API WiGLE не вызывается. При включении BeaconDB доступны только ручные запросы по сотовым ячейкам, без загрузки наблюдений."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Text(databaseFacts, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    OutlinedButton(onClick = onClearData, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Outlined.DeleteOutline, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Удалить локальную базу")
                    }
                    Text(
                        "Онлайн-карта OpenFreeMap получает обычные запросы тайлов и может видеть IP-адрес и просматриваемую область. Радиоидентификаторы к запросам не добавляются.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }

        SettingsSectionToggle(
            title = "Прочее и диагностика",
            summary = "Подробные логи, просмотр и копирование для поиска ошибок",
            expanded = showDiagnosticOptions,
            onClick = { showDiagnosticOptions = !showDiagnosticOptions }
        )
        if (showDiagnosticOptions) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SettingSwitch(
                        "Подробное логирование",
                        "Записывает результаты радио-сканов, причины отказа фикса, ошибки mock GPS и состояние службы.",
                        detailedLogging,
                        onDetailedLogging
                    )
                    Text(
                        "Журнал хранится только на устройстве. В него не записываются точные координаты, MAC/BSSID и имена устройств. Логи приложения не включают системный Android logcat.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Text("Максимальный размер: $diagnosticLogMaxKb КБ", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = pendingDiagnosticLogMaxKb,
                        onValueChange = { pendingDiagnosticLogMaxKb = it },
                        onValueChangeFinished = {
                            val requested = pendingDiagnosticLogMaxKb.roundToInt()
                            val normalized = (((requested + 32) / 64) * 64).coerceIn(
                                DiagnosticLogStore.MIN_MAX_KB, DiagnosticLogStore.MAX_MAX_KB
                            )
                            pendingDiagnosticLogMaxKb = normalized.toFloat()
                            if (normalized != diagnosticLogMaxKb) onDiagnosticLogMaxKb(normalized)
                        },
                        valueRange = DiagnosticLogStore.MIN_MAX_KB.toFloat()..DiagnosticLogStore.MAX_MAX_KB.toFloat(),
                        steps = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onViewDiagnosticLogs,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Outlined.History, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Смотреть логи")
                        }
                        OutlinedButton(
                            onClick = onClearDiagnosticLogs,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Очистить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionToggle(
    title: String,
    summary: String,
    expanded: Boolean,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerHigh
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
        }
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
private fun ClearGpsTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
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
                    AppTab.OBD2 -> "Автопараметры · только чтение"
                    AppTab.SETTINGS -> "Источники и приватность"
                }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            if (tab != AppTab.OBD2) {
                IconButton(onClick = onCenter) { Icon(Icons.Outlined.MyLocation, "Моё местоположение") }
            }
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
        Text("OpenFreeMap · OpenMapTiles · © OpenStreetMap", modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            color = if (dark) Color.White else Color(0xFF253044), style = MaterialTheme.typography.labelSmall)
    }
}

private fun effectiveScanIntervalSeconds(configuredSeconds: Int, speedKmh: Float?, adaptive: Boolean): Int {
    val configured = configuredSeconds.coerceIn(1, 30)
    val movingFast = speedKmh != null && speedKmh.isFinite() && speedKmh > 25f
    return if (adaptive && movingFast) (configured / 2).coerceAtLeast(1) else configured
}

private fun recommendedMapPointLimit(context: Context): Int {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
    return if (activityManager?.isLowRamDevice == true || Runtime.getRuntime().maxMemory() < 256L * 1024L * 1024L) 400 else 2_500
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
private fun dateTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
private const val RECORD_INTERVAL_MS = 15_000L


package org.cleargps

import android.content.SharedPreferences
import org.cleargps.core.RiskBreakdown

/** Private, bounded summary of the latest score explanation; never stores raw sensor payloads. */
internal object RiskBreakdownStore {
    private const val MAX_AGE_NANOS = 30_000_000_000L
    private const val KEY_AVAILABLE = "risk_breakdown_available"
    private const val KEY_AT_NANOS = "risk_breakdown_at_nanos"
    private const val KEY_MOTION_EVALUATED = "risk_motion_evaluated"
    private const val KEY_MOTION_SCORE = "risk_motion_score"
    private const val KEY_MOTION_REASON = "risk_motion_reason"
    private const val KEY_CROSS_EVALUATED = "risk_cross_source_evaluated"
    private const val KEY_CROSS_SCORE = "risk_cross_source_score"
    private const val KEY_CROSS_REASON = "risk_cross_source_reason"
    private const val KEY_SIGNAL_EVALUATED = "risk_gnss_signal_evaluated"
    private const val KEY_SIGNAL_SCORE = "risk_gnss_signal_score"
    private const val KEY_SIGNAL_REASON = "risk_gnss_signal_reason"
    private const val KEY_MOTION_CROSS_BONUS = "risk_motion_cross_bonus"
    private const val KEY_SIGNAL_BONUS = "risk_gnss_corroboration_bonus"
    private const val KEY_COMBINED_BEFORE_ZONE = "risk_combined_before_zone"
    private const val KEY_ZONE_FLOOR_ACTIVE = "risk_zone_floor_active"
    private const val KEY_ZONE_RAISED_SCORE = "risk_zone_raised_score"
    private const val KEY_RAW_SCORE = "risk_raw_combined_score"
    private const val KEY_CLAMPED = "risk_clamped_at_hundred"
    private const val KEY_TEMPORAL_FLOOR = "risk_temporal_hold_floor"
    private const val KEY_FINAL_SCORE = "risk_breakdown_final_score"

    fun put(editor: SharedPreferences.Editor, breakdown: RiskBreakdown?, atElapsedRealtimeNanos: Long) {
        val value = breakdown?.takeIf { it.available && it.finalScore in 0..100 }
        editor.putBoolean(KEY_AVAILABLE, value != null)
            .putLong(KEY_AT_NANOS, if (value != null) atElapsedRealtimeNanos else 0L)
            .putBoolean(KEY_MOTION_EVALUATED, value?.motionEvaluated == true)
            .putInt(KEY_MOTION_SCORE, value?.motionScore ?: 0)
            .putString(KEY_MOTION_REASON, value?.motionReason.orEmpty())
            .putBoolean(KEY_CROSS_EVALUATED, value?.crossSourceEvaluated == true)
            .putInt(KEY_CROSS_SCORE, value?.crossSourceScore ?: 0)
            .putString(KEY_CROSS_REASON, value?.crossSourceReason.orEmpty())
            .putBoolean(KEY_SIGNAL_EVALUATED, value?.gnssSignalEvaluated == true)
            .putInt(KEY_SIGNAL_SCORE, value?.gnssSignalScore ?: 0)
            .putString(KEY_SIGNAL_REASON, value?.gnssSignalReason.orEmpty())
            .putInt(KEY_MOTION_CROSS_BONUS, value?.motionCrossSourceBonus ?: 0)
            .putInt(KEY_SIGNAL_BONUS, value?.gnssCorroborationBonus ?: 0)
            .putInt(KEY_COMBINED_BEFORE_ZONE, value?.combinedBeforeZone ?: 0)
            .putBoolean(KEY_ZONE_FLOOR_ACTIVE, value?.zoneFloorActive == true)
            .putBoolean(KEY_ZONE_RAISED_SCORE, value?.zoneRaisedScore == true)
            .putInt(KEY_RAW_SCORE, value?.rawCombinedScore ?: 0)
            .putBoolean(KEY_CLAMPED, value?.clampedAtHundred == true)
            .putBoolean(KEY_TEMPORAL_FLOOR, value?.temporalHoldFloorApplied == true)
            .putInt(KEY_FINAL_SCORE, value?.finalScore ?: 0)
    }

    fun write(prefs: SharedPreferences, breakdown: RiskBreakdown?, atElapsedRealtimeNanos: Long) {
        val editor = prefs.edit()
        put(editor, breakdown, atElapsedRealtimeNanos)
        editor.apply()
    }

    fun clear(prefs: SharedPreferences) = write(prefs, null, 0L)

    fun read(prefs: SharedPreferences, nowElapsedRealtimeNanos: Long): RiskBreakdown? {
        if (!prefs.getBoolean(KEY_AVAILABLE, false)) return null
        val at = prefs.getLong(KEY_AT_NANOS, 0L)
        if (nowElapsedRealtimeNanos <= 0L || at <= 0L ||
            nowElapsedRealtimeNanos < at || nowElapsedRealtimeNanos - at > MAX_AGE_NANOS
        ) return null
        val motionScore = prefs.getInt(KEY_MOTION_SCORE, -1)
        val crossScore = prefs.getInt(KEY_CROSS_SCORE, -1)
        val signalScore = prefs.getInt(KEY_SIGNAL_SCORE, -1)
        val motionBonus = prefs.getInt(KEY_MOTION_CROSS_BONUS, -1)
        val signalBonus = prefs.getInt(KEY_SIGNAL_BONUS, -1)
        val combinedBeforeZone = prefs.getInt(KEY_COMBINED_BEFORE_ZONE, -1)
        val rawScore = prefs.getInt(KEY_RAW_SCORE, -1)
        val finalScore = prefs.getInt(KEY_FINAL_SCORE, -1)
        if (motionScore !in 0..100 || crossScore !in 0..100 || signalScore !in 0..100 ||
            motionBonus !in 0..20 || signalBonus !in 0..15 || combinedBeforeZone !in 0..135 ||
            rawScore !in 0..100 || finalScore !in 0..100
        ) return null

        return RiskBreakdown(
            available = true,
            motionEvaluated = prefs.getBoolean(KEY_MOTION_EVALUATED, false),
            motionScore = motionScore,
            motionReason = prefs.getString(KEY_MOTION_REASON, null)?.takeIf { it.isNotBlank() },
            crossSourceEvaluated = prefs.getBoolean(KEY_CROSS_EVALUATED, false),
            crossSourceScore = crossScore,
            crossSourceReason = prefs.getString(KEY_CROSS_REASON, null)?.takeIf { it.isNotBlank() },
            gnssSignalEvaluated = prefs.getBoolean(KEY_SIGNAL_EVALUATED, false),
            gnssSignalScore = signalScore,
            gnssSignalReason = prefs.getString(KEY_SIGNAL_REASON, null)?.takeIf { it.isNotBlank() },
            motionCrossSourceBonus = motionBonus,
            gnssCorroborationBonus = signalBonus,
            combinedBeforeZone = combinedBeforeZone,
            zoneFloorActive = prefs.getBoolean(KEY_ZONE_FLOOR_ACTIVE, false),
            zoneRaisedScore = prefs.getBoolean(KEY_ZONE_RAISED_SCORE, false),
            rawCombinedScore = rawScore,
            clampedAtHundred = prefs.getBoolean(KEY_CLAMPED, false),
            temporalHoldFloorApplied = prefs.getBoolean(KEY_TEMPORAL_FLOOR, false),
            finalScore = finalScore
        )
    }
}


package org.cleargps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RiskBreakdownPanel(
    breakdown: org.cleargps.core.RiskBreakdown?,
    currentRiskScore: Int,
    monitorEnabled: Boolean,
    radioOnly: Boolean
) {
    if (!monitorEnabled || radioOnly || breakdown == null ||
        !breakdown.available || breakdown.finalScore != currentRiskScore
    ) return

    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Из чего сложился уровень риска", style = MaterialTheme.typography.titleSmall)
            Text(
                "Баллы компонентов — внутренние веса эвристики, не проценты вероятности. Детектор объединяет их по правилам; значения нельзя просто сложить.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
            BreakdownRow("Траектория и скорость", breakdown.motionEvaluated, breakdown.motionScore,
                breakdown.motionReason, "Недостаточно свежих последовательных GPS-фиксов или корректных данных скорости.")
            BreakdownRow("GPS ↔ независимая сетевая позиция", breakdown.crossSourceEvaluated, breakdown.crossSourceScore,
                breakdown.crossSourceReason, "Сетевая позиция отсутствует, устарела или её точности недостаточно для сравнения.")
            BreakdownRow("Сырые GNSS/RF-признаки", breakdown.gnssSignalEvaluated, breakdown.gnssSignalScore,
                breakdown.gnssSignalReason, "Нет свежих пригодных сигнальных метрик GNSS.")

            if (breakdown.motionCrossSourceBonus > 0) Text(
                "Подтверждение независимым источником: +${breakdown.motionCrossSourceBonus} внутренних баллов за совместное срабатывание траектории и расхождения источников.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
            if (breakdown.gnssCorroborationBonus > 0) Text(
                "Поддержка GNSS/RF: +${breakdown.gnssCorroborationBonus} внутренних баллов к другим сработавшим группам (ограничено 15).",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
            if (breakdown.zoneFloorActive) Text(
                if (breakdown.zoneRaisedScore) {
                    "Зона риска установила нижний порог 75: без порога было ${breakdown.combinedBeforeZone} внутренних баллов."
                } else {
                    "Зона риска учтена с нижним порогом 75; до применения порога уже было ${breakdown.combinedBeforeZone} внутренних баллов."
                },
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
            if (breakdown.clampedAtHundred) Text(
                "Совокупный балл превысил шкалу; значение ограничено максимумом 100.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
            if (breakdown.temporalHoldFloorApplied) Text(
                "Тревога удерживается по гистерезису: пока не накоплены последовательные спокойные фиксы, отображаемый балл не ниже 60.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Объединённый балл", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text("${breakdown.rawCombinedScore}/100", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Отображаемый уровень риска", style = MaterialTheme.typography.titleSmall)
                Text("${breakdown.finalScore}/100", style = MaterialTheme.typography.titleSmall)
            }
            LinearProgressIndicator(
                progress = { breakdown.finalScore.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun BreakdownRow(
    title: String,
    evaluated: Boolean,
    score: Int,
    reason: String?,
    notEvaluatedText: String
) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (evaluated) Text("${score}/100", style = MaterialTheme.typography.bodySmall)
        }
        if (!evaluated) {
            Text(notEvaluatedText, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        } else {
            LinearProgressIndicator(
                progress = { score.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth()
            )
            Text(
                reason ?: "В этой группе пороговый тревожный признак не сработал.",
                style = MaterialTheme.typography.labelSmall,
                color = if (score >= 60) colors.error else colors.onSurfaceVariant
            )
        }
    }
}

