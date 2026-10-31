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
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import org.cleargps.core.RiskFeedbackCodec
import org.cleargps.core.RiskFeedbackLabel
import org.cleargps.core.RiskFeedbackRecord
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
private enum class PointsView { POINTS, HISTORY, SOURCES }
private const val RADIO_POINT_LIST_LIMIT = 5_000
private enum class DatabaseDataSource(val key: String, val title: String) {
    LOCAL("local", "Локальная"),
    BEACONDB("beacondb", "BeaconDB · оценка"),
    OPENCELLID("opencellid", "OpenCellID · соты")
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

private fun openBatteryOptimizationExemption(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
        Toast.makeText(
            context,
            "На этой версии Android отдельный список оптимизации батареи недоступен.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true) {
        Toast.makeText(
            context,
            "Clear GPS уже исключён из оптимизации батареи.",
            Toast.LENGTH_LONG
        ).show()
        return
    }

    val packageUri = Uri.parse("package:${context.packageName}")
    try {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(packageUri)
        )
        return
    } catch (_: Exception) {
        // Some Android builds omit the direct-request screen; fall back to system settings.
    }

    try {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        return
    } catch (_: Exception) {
        // Older or vendor-modified builds may not expose the general battery list.
    }

    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        )
    } catch (_: Exception) {
        Toast.makeText(
            context,
            "Не удалось открыть системные настройки батареи. Найди Clear GPS в настройках приложений.",
            Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
fun ClearGpsApp(
    onMapViewCreated: (MapView) -> Unit,
    onMapViewDisposed: (MapView) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val prefs = remember { context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE) }
    val repository = remember { RadioGeolocationRepository(context.applicationContext) }
    val database = remember { LocalRadioDatabase(context.applicationContext) }
    val radioRepository = remember { RadioRepository(database) }
    val initialRadioPointCounts = remember { radioRepository.pointCounts() }
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
    var pendingPositionMode by remember { mutableStateOf<PositionMode?>(null) }
    val mapIsDark = when (mapThemeMode) {
        MapThemeMode.SYSTEM -> systemDark
        MapThemeMode.LIGHT -> false
        MapThemeMode.DARK -> true
    }

    var liveLocation by remember { mutableStateOf<Location?>(null) }
    // Physical GPS data is kept separate so network/passive/mock fixes cannot train calibration.
    var liveGnssLocation by remember { mutableStateOf<Location?>(null) }
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
    var airportAvoidanceEnabled by remember {
        mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_AIRPORT_AVOIDANCE_ENABLED, true))
    }
    var airportRadiusMeters by remember {
        mutableStateOf(prefs.getInt(
            ClearGpsPreferences.KEY_AIRPORT_RADIUS_METERS,
            SpoofZoneService.DEFAULT_AIRPORT_RADIUS_METERS
        ).coerceIn(SpoofZoneService.MIN_AIRPORT_RADIUS_METERS, SpoofZoneService.MAX_AIRPORT_RADIUS_METERS))
    }
    var airportExclusions by remember {
        mutableStateOf(prefs.getString(ClearGpsPreferences.KEY_AIRPORT_EXCLUSIONS, "").orEmpty())
    }
    var airportCatalogOnlineEnabled by remember {
        mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_AIRPORT_CATALOG_ONLINE_ENABLED, false))
    }
    var airportCatalogSnapshot by remember { mutableStateOf(AirportCatalogStore.load(context)) }
    var airportCatalogRefreshing by remember { mutableStateOf(false) }
    var draftZone by remember { mutableStateOf(emptyList<ZonePoint>()) }
    var mapTapMode by remember { mutableStateOf(MapTapMode.NONE) }
    var selectedTab by remember { mutableStateOf(AppTab.MAP) }
    var obd2Adapters by remember { mutableStateOf(emptyList<Obd2Adapter>()) }
    var selectedObd2Address by remember {
        mutableStateOf(prefs.getString(Obd2SharedState.KEY_ADAPTER_ADDRESS, "").orEmpty())
    }
    var pendingObdBleScan by remember { mutableStateOf(false) }
    var obd2BleScanning by remember { mutableStateOf(false) }
    var obd2Connected by remember { mutableStateOf(false) }
    var obd2Connecting by remember { mutableStateOf(false) }
    var obd2ConnectionRequested by remember { mutableStateOf(false) }
    var obd2ConnectJob by remember { mutableStateOf<Job?>(null) }
    var obd2ReconnectJob by remember { mutableStateOf<Job?>(null) }
    var obd2Telemetry by remember { mutableStateOf(Obd2Telemetry()) }
    var obd2ExternalBroadcastEnabled by remember {
        mutableStateOf(prefs.getBoolean(Obd2ExternalTelemetryBroadcast.KEY_ENABLED, false))
    }
    val obd2SpeedCalibration = remember { Obd2SpeedCalibration() }
    var obd2CalibrationSnapshot by remember { mutableStateOf(Obd2SpeedCalibrationSnapshot()) }
    val obd2GearRatioLearner = remember { Obd2GearRatioLearner() }
    var obd2GearLearningSnapshot by remember { mutableStateOf(Obd2GearLearningSnapshot()) }
    val obd2DriveMetricsTracker = remember { Obd2DriveMetricsTracker() }
    var obd2DriveMetricsSnapshot by remember { mutableStateOf(Obd2DriveMetricsSnapshot()) }
    var obd2Status by remember { mutableStateOf("Не подключено. Выбери сопряжённый Bluetooth OBD-II адаптер.") }
    var obd2StatusIsError by remember { mutableStateOf(false) }
    var fusionNowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var pointsView by remember { mutableStateOf(PointsView.POINTS) }
    var points by remember { mutableStateOf(radioRepository.loadLocalPoints(limit = RADIO_POINT_LIST_LIMIT)) }
    var mapPoints by remember { mutableStateOf(emptyList<RadioPointRecord>()) }
    var mapPointsRevision by remember { mutableStateOf(0) }
    var mapPointLimit by remember {
        mutableStateOf(
            RadioMapPointLimitPolicy.normalizeLimit(
                prefs.getInt(ClearGpsPreferences.KEY_MAP_RADIO_POINT_LIMIT, RadioMapPointLimitPolicy.DEFAULT_LIMIT)
            )
        )
    }
    var geolocatedMapPointCount by remember { mutableStateOf(initialRadioPointCounts.geolocated) }
    var mapPointsLoading by remember { mutableStateOf(false) }
    var mapPointsLoadJob by remember { mutableStateOf<Job?>(null) }
    var history by remember { mutableStateOf(database.listHistory(150)) }
    var radioSources by remember { mutableStateOf(database.listRadioSources()) }
    var sourceDaysBySource by remember { mutableStateOf(database.listRadioSourceDays().groupBy { it.sourceId }) }
    var sourceSegmentsBySource by remember { mutableStateOf(database.listRadioSourceSegments().groupBy { it.sourceId }) }
    var pointCount by remember { mutableStateOf(initialRadioPointCounts.total) }
    var observationCount by remember { mutableStateOf(database.observationCount()) }
    var observationRetentionLimit by remember { mutableStateOf(database.observationRetentionLimit()) }
    var retentionLimitUpdating by remember { mutableStateOf(false) }
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

    var localPositionSourceEnabled by remember {
        mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_LOCAL_ENABLED, true))
    }
    // Use any available BeaconDB cache by default; its separate network-consent setting
    // remains opt-in and false by default, so this cannot start an online request.
    var beaconDbPositionSourceEnabled by remember {
        mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_BEACONDB_ENABLED, true))
    }
    var openCellIdPositionSourceEnabled by remember {
        mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_OPENCELLID_ENABLED, true))
    }
    var useWifi by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_USE_WIFI, true)) }
    var useCells by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_USE_CELLS, true)) }
    var useBle by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_USE_BLE, true)) }
    var wifiSsidBlacklist by remember {
        mutableStateOf(prefs.getString(ClearGpsPreferences.KEY_WIFI_SSID_BLACKLIST, RadioSsidFilter.DEFAULT_PATTERNS) ?: RadioSsidFilter.DEFAULT_PATTERNS)
    }
    var scanIntervalSeconds by remember { mutableStateOf(prefs.getInt(ClearGpsPreferences.KEY_SCAN_INTERVAL_SECONDS, 15).coerceIn(1, 30)) }
    var adaptiveScan by remember { mutableStateOf(prefs.getBoolean(ClearGpsPreferences.KEY_ADAPTIVE_SCAN, true)) }
    var batteryProfile by remember {
        mutableStateOf(BatteryProfile.fromKey(prefs.getString(ClearGpsPreferences.KEY_BATTERY_PROFILE, BatteryProfile.NORMAL.key)))
    }
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
    var openCellIdRecords by remember { mutableStateOf(database.listOpenCellIdCells()) }
    var openCellIdKeyConfigured by remember { mutableStateOf(OpenCellIdKeyStore.hasKey(context)) }
    var openCellIdKeyInput by remember { mutableStateOf("") }
    var openCellIdEnabled by remember { mutableStateOf(OpenCellIdKeyStore.isEnabled(context)) }
    var openCellIdLoading by remember { mutableStateOf(false) }
    var openCellIdMessage by remember {
        mutableStateOf("OpenCellID выключен. Сохрани личный API-ключ и отдельно разреши ручные запросы.")
    }
    var openCellIdLookupJob by remember { mutableStateOf<Job?>(null) }
    var openCellIdRequestToken by remember { mutableStateOf(0) }
    var monitorEnabled by remember { mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_MONITORING, false)) }
    var mockEnabled by remember { mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_MOCK_ENABLED, false)) }
    var mockOutputActive by remember { mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE, false)) }
    var publishLowAccuracy by remember {
        mutableStateOf(prefs.getBoolean(LocationMonitorService.KEY_PUBLISH_LOW_ACCURACY, false))
    }
    var filterState by remember { mutableStateOf(prefs.getString(LocationMonitorService.KEY_STATE, FilterState.WAITING.name) ?: FilterState.WAITING.name) }
    var riskScore by remember { mutableStateOf(prefs.getInt(LocationMonitorService.KEY_SCORE, 0)) }
    var riskEventSource by remember {
        mutableStateOf(prefs.getString(LocationMonitorService.KEY_SOURCE, "NONE") ?: "NONE")
    }
    var riskFeedbackCount by remember { mutableStateOf(RiskFeedbackStore.count(prefs)) }
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
    var gnssReplayRecording by remember { mutableStateOf(GnssReplayRecorder.isRecording(context)) }
    var gnssReplayBytes by remember { mutableStateOf(GnssReplayRecorder.fileSize(context)) }
    var gnssReplayStopReason by remember { mutableStateOf(GnssReplayRecorder.lastStopReason(context)) }
    var gnssReplaySummary by remember { mutableStateOf("") }
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
    var showSourceExportWarning by remember { mutableStateOf(false) }
    var pendingSourceExportId by remember { mutableStateOf<Long?>(null) }
    var pendingSourceExportDay by remember { mutableStateOf<String?>(null) }
    var pendingSourceExportType by remember { mutableStateOf<String?>(null) }
    var pendingSourceExportFileName by remember { mutableStateOf<String?>(null) }
    var showZoneNameDialog by remember { mutableStateOf(false) }
    var zoneName by remember { mutableStateOf("") }
    var zoneToRename by remember { mutableStateOf<SavedRiskZone?>(null) }
    var zoneRenameText by remember { mutableStateOf("") }
    var showRadioOnlyInfo by remember { mutableStateOf(false) }
    var showMapLegend by remember { mutableStateOf(false) }
    var pendingRecordScan by remember { mutableStateOf(false) }

    // Load and publish the local database UI from the caller's existing coroutine.
    // A scan must not start a nested rememberCoroutineScope job: that scope may already
    // have left the composition by the time an asynchronous scan returns.
    suspend fun refreshMapPointsInCurrentCoroutine(limit: Int = mapPointLimit) {
        val loaded = withContext(Dispatchers.IO) {
            radioRepository.loadMapPoints(limit) to radioRepository.pointCounts().geolocated
        }
        if (limit == mapPointLimit) {
            mapPoints = loaded.first
            geolocatedMapPointCount = loaded.second
            mapPointsRevision++
        }
    }

    fun changeMapPointLimit(requested: Int) {
        val normalized = RadioMapPointLimitPolicy.normalizeLimit(requested)
        if (normalized == mapPointLimit) return
        mapPointLimit = normalized
        prefs.edit().putInt(ClearGpsPreferences.KEY_MAP_RADIO_POINT_LIMIT, normalized).apply()
        mapPointsLoadJob?.cancel()
        mapPointsLoadJob = scope.launch {
            mapPointsLoading = true
            try {
                refreshMapPointsInCurrentCoroutine(normalized)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(context, "Не удалось загрузить точки карты: " +
                    (error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
            } finally {
                if (mapPointLimit == normalized) mapPointsLoading = false
            }
        }
    }

    suspend fun refreshLocalUiInCurrentCoroutine() {
        val query = searchQuery
        points = withContext(Dispatchers.IO) {
            if (query.isBlank()) radioRepository.loadLocalPoints(limit = RADIO_POINT_LIST_LIMIT) else radioRepository.loadLocalPoints(query = query, limit = RADIO_POINT_LIST_LIMIT)
        }
        refreshMapPointsInCurrentCoroutine(mapPointLimit)
        history = withContext(Dispatchers.IO) { database.listHistory(150) }
        radioSources = withContext(Dispatchers.IO) { database.listRadioSources() }
        sourceDaysBySource = withContext(Dispatchers.IO) { database.listRadioSourceDays().groupBy { it.sourceId } }
        sourceSegmentsBySource = withContext(Dispatchers.IO) { database.listRadioSourceSegments().groupBy { it.sourceId } }
        pointCount = withContext(Dispatchers.IO) { radioRepository.pointCounts().total }
        observationCount = withContext(Dispatchers.IO) { database.observationCount() }
        zones = ZoneStore.loadAll(context)
    }

    fun refreshLocalUi() {
        scope.launch { refreshLocalUiInCurrentCoroutine() }
    }

    suspend fun showRadioDeletionUndoSnackbar(message: String, undoToken: String?) {
        if (undoToken == null) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            return
        }
        val result = try {
            snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Отменить",
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                database.discardRadioSourceDeletionUndo(undoToken)
            }
            throw cancelled
        }
        if (result == SnackbarResult.ActionPerformed) {
            val restored = withContext(Dispatchers.IO) {
                database.undoRadioSourceDeletion(undoToken)
            }
            refreshLocalUiInCurrentCoroutine()
            snackbarHostState.showSnackbar(
                if (restored) "Удаление отменено, данные восстановлены."
                else "Отмена недоступна: срок истёк или источник уже изменён.",
                withDismissAction = true,
                duration = SnackbarDuration.Short
            )
        } else {
            withContext(Dispatchers.IO) { database.discardRadioSourceDeletionUndo(undoToken) }
        }
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
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val point = database.findPoint(pointId) ?: return@withContext null
                    point to database.listObservations(point)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: android.database.sqlite.SQLiteException) {
                Toast.makeText(
                    context,
                    "Не удалось открыть точку: ошибка локальной базы данных.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
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
        if (mode.usesRadio || mode.gpsOnly) {
            liveLocation = null
            liveGnssLocation = null
        }
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
                val liveFixForZone = if (positionMode.usesRadio) null
                    else liveLocation?.takeIf { now - it.time in 0..30_000L }
                val observedPosition = if (positionMode.usesRadio) {
                    trustedRadioEstimate?.let { ZonePoint(it.latitude, it.longitude) }
                } else {
                    liveFixForZone?.let { ZonePoint(it.latitude, it.longitude) }
                }
                val observedAccuracyMeters = if (positionMode.usesRadio) {
                    trustedRadioEstimate?.accuracyMeters
                } else {
                    liveFixForZone?.takeIf {
                        it.hasAccuracy() && it.accuracy.isFinite() && it.accuracy >= 0f
                    }?.accuracy?.toDouble()
                }
                val insideRiskZone = observedPosition?.let { point ->
                    zones.any { zone ->
                        zone.enabled && ZoneGeometry.contains(point, zone.points, observedAccuracyMeters)
                    }
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
        if (positionMode.gpsOnly && !hasFineLocationPermission(context)) {
            serviceStatus = "Для режима «Только GPS» требуется разрешение «Точное местоположение»."
            Toast.makeText(context, serviceStatus, Toast.LENGTH_LONG).show()
            return
        }
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
        val requestedPositionMode = pendingPositionMode
        if (requestedPositionMode != null) {
            pendingPositionMode = null
            if (hasFineLocationPermission(context)) {
                applyPositionMode(requestedPositionMode)
            } else {
                serviceStatus = "Для режима «Только GPS» требуется разрешение «Точное местоположение»."
                Toast.makeText(context, serviceStatus, Toast.LENGTH_LONG).show()
            }
        } else if (pendingMonitorStart) {
            pendingMonitorStart = false
            val requiredPermissionGranted = if (positionMode.gpsOnly) {
                hasFineLocationPermission(context)
            } else {
                hasAnyLocationPermission(context)
            }
            if (requiredPermissionGranted) {
                startMonitoringWithPermission(pendingMockEnable)
                pendingMockEnable = false
            } else {
                pendingMockEnable = false
                val message = if (positionMode.gpsOnly) {
                    "Режим «Только GPS» не запущен: дай Clear GPS разрешение «Точное местоположение»."
                } else {
                    "Мониторинг не запущен: разрешение геолокации отклонено."
                }
                serviceStatus = message
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
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

    fun selectPositionMode(requestedMode: PositionMode) {
        if (requestedMode.gpsOnly && !hasFineLocationPermission(context)) {
            pendingPositionMode = requestedMode
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            applyPositionMode(requestedMode)
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
        beaconDbMessage = "Сканирую доступные Wi-Fi, BLE-маяки и соты; запрос ещё не отправлен…"
        val queryJob = scope.launch {
            try {
                val result = repository.lookupBeaconDbAllRadios()
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

    fun startOpenCellIdLookup() {
        if (!openCellIdEnabled || !OpenCellIdKeyStore.isEnabled(context)) {
            openCellIdMessage = "Ручные запросы OpenCellID выключены. Сетевого обращения не было."
            return
        }
        if (openCellIdLoading || openCellIdLookupJob?.isActive == true) return
        val apiKey = OpenCellIdKeyStore.read(context)
        if (apiKey.isNullOrBlank()) {
            openCellIdKeyConfigured = false
            openCellIdMessage = "Сохрани действующий API-ключ OpenCellID. Сетевого обращения не было."
            return
        }
        if (CrashLoopGuard.isSafeModeActive(context)) {
            openCellIdMessage = "Безопасный режим после повторных сбоев: запросы OpenCellID заблокированы."
            return
        }

        val requestToken = openCellIdRequestToken + 1
        openCellIdRequestToken = requestToken
        openCellIdLoading = true
        openCellIdMessage = "Получаю свежие CellInfo Android. Запрос к OpenCellID ещё не отправлен…"
        val queryJob = scope.launch {
            try {
                val cells = repository.currentCellsForOpenCellId()
                if (requestToken != openCellIdRequestToken) return@launch
                if (cells.isEmpty()) {
                    openCellIdMessage =
                        "Android не предоставил свежих поддерживаемых GSM/UMTS/LTE/NR-ячеек. " +
                        "Проверь точное разрешение геолокации и доступность CellInfo на устройстве."
                    return@launch
                }

                var queried = 0
                var found = 0
                var notFound = 0
                var reused = 0
                var limited = false
                var stoppedMessage = ""
                val staleCells = ArrayList<OpenCellIdCellKey>()
                for ((cell, _) in cells) {
                    if (requestToken != openCellIdRequestToken || !OpenCellIdKeyStore.isEnabled(context)) break
                    val cached = withContext(Dispatchers.IO) { database.getOpenCellIdCell(cell) }
                    if (!OpenCellIdLookupPolicy.shouldLookup(cached, System.currentTimeMillis())) {
                        reused++
                        if (cached?.status == "found") found++ else notFound++
                    } else {
                        staleCells.add(cell)
                    }
                }

                if (staleCells.isNotEmpty() && requestToken == openCellIdRequestToken &&
                    OpenCellIdKeyStore.isEnabled(context)
                ) {
                    when (OpenCellIdUsageBudget.tryStartBatch(context)) {
                        OpenCellIdBudgetBlock.NONE -> {
                            val batch = staleCells.take(OpenCellIdLookupPolicy.MAX_LOOKUPS_PER_ACTION)
                            limited = staleCells.size > batch.size
                            for (cell in batch) {
                                if (requestToken != openCellIdRequestToken || !OpenCellIdKeyStore.isEnabled(context)) break
                                // Reserve before opening the socket. A timeout may still have consumed
                                // an API credit, so failed attempts remain inside the local safety budget.
                                if (!OpenCellIdUsageBudget.reserveRequest(context)) {
                                    stoppedMessage = "Достигнут дневной лимит Clear GPS."
                                    break
                                }
                                queried++
                                val response = OpenCellIdClient.lookup(context, apiKey, cell)
                                if (response.status == OpenCellIdLookupStatus.RATE_LIMITED) {
                                    OpenCellIdUsageBudget.markServerLimitReached(context)
                                    stoppedMessage = response.message + " Повтор будет разрешён после смены UTC-даты."
                                    break
                                }
                                val record = response.record
                                if (record != null) {
                                    withContext(Dispatchers.IO) { database.saveOpenCellIdCell(record) }
                                    if (response.status == OpenCellIdLookupStatus.FOUND) found++ else notFound++
                                } else {
                                    stoppedMessage = response.message
                                    break
                                }
                                if (queried < batch.size) delay(150L)
                            }
                        }
                        OpenCellIdBudgetBlock.DAILY_BUDGET ->
                            stoppedMessage = "Дневной лимит Clear GPS исчерпан; счётчик сбросится в 00:00 UTC."
                        OpenCellIdBudgetBlock.SERVER_LIMIT ->
                            stoppedMessage = "OpenCellID сообщил об исчерпании серверной квоты; Clear GPS проверит доступность после следующей UTC-даты."
                        OpenCellIdBudgetBlock.COOLDOWN ->
                            stoppedMessage = "Экономия запросов: между пакетами OpenCellID выдерживается пауза 15 минут."
                    }
                }
                openCellIdRecords = withContext(Dispatchers.IO) { database.listOpenCellIdCells() }
                if (requestToken == openCellIdRequestToken) {
                    openCellIdMessage = buildString {
                        append("Текущих сот: ").append(cells.size)
                        append(" · новых запросов: ").append(queried)
                        append(" · найдены: ").append(found)
                        append(" · не найдены: ").append(notFound)
                        append(" · взято из кэша: ").append(reused).append(".")
                        if (limited) append(" Достигнут лимит одного запуска: ")
                            .append(OpenCellIdLookupPolicy.MAX_LOOKUPS_PER_ACTION).append(" запросов.")
                        if (stoppedMessage.isNotBlank()) append(" ").append(stoppedMessage)
                        if (!OpenCellIdKeyStore.isEnabled(context)) append(" Запрос остановлен: разрешение выключено.")
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (requestToken == openCellIdRequestToken) {
                    openCellIdMessage = "Не удалось получить данные OpenCellID: " +
                        error.javaClass.simpleName + ". Проверь интернет и разрешение точной геолокации."
                }
            } finally {
                if (requestToken == openCellIdRequestToken) {
                    openCellIdLoading = false
                    openCellIdLookupJob = null
                }
            }
        }
        openCellIdLookupJob = queryJob
    }

    fun saveOpenCellIdApiKey() {
        val saved = OpenCellIdKeyStore.save(context, openCellIdKeyInput)
        if (saved) {
            openCellIdKeyConfigured = true
            openCellIdKeyInput = ""
            openCellIdMessage = "API-ключ зашифрован Android Keystore и сохранён локально."
        } else {
            openCellIdMessage = "Не удалось сохранить ключ. Проверь формат ключа и доступность Android Keystore."
        }
    }

    fun forgetOpenCellIdApiKey() {
        openCellIdRequestToken += 1
        openCellIdLookupJob?.cancel()
        openCellIdLookupJob = null
        openCellIdLoading = false
        OpenCellIdKeyStore.clear(context)
        openCellIdKeyConfigured = false
        openCellIdEnabled = false
        openCellIdKeyInput = ""
        openCellIdMessage = "API-ключ удалён из зашифрованного хранилища; запросы выключены."
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

    val exportGnssReplayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { GnssReplayRecorder.readLogBytes(context) }
                if (data.isEmpty()) throw IllegalStateException("Нет доступного replay-файла.")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { it.write(data) }
                        ?: throw IllegalStateException("Не удалось открыть файл для записи.")
                }
                Toast.makeText(context, "Replay сохранён в выбранный файл. Автоматической отправки нет.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, "Ошибка экспорта replay: " + (error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }
    val openGnssReplayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Не удалось открыть replay-файл.")
                    input.use { stream ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > org.cleargps.core.GnssReplayCodec.MAX_BYTES)
                                throw IllegalArgumentException("Replay превышает лимит 2 МиБ.")
                            out.write(buffer, 0, read)
                        }
                        out.toByteArray()
                    }
                }
                val importOutcome = withContext(Dispatchers.Default) {
                    val sourceText = String(raw, Charsets.UTF_8)
                    val firstLine = sourceText.removePrefix("\uFEFF").lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    if (org.cleargps.core.GnssReplayCodec.isSupportedHeader(firstLine)) {
                        val decoded = org.cleargps.core.GnssReplayCodec.decode(sourceText)
                        if (decoded.error != null) throw IllegalArgumentException(decoded.error)
                        Triple(org.cleargps.core.GnssReplayRunner().replay(decoded), false, "")
                    } else {
                        val imported = org.cleargps.core.GnssLoggerCsvImporter.decode(sourceText)
                        if (imported.decoded.error != null) throw IllegalArgumentException(imported.decoded.error)
                        val details = "Google GnssLogger CSV: Raw " + imported.stats.rawRows +
                            ", Fix " + imported.stats.fixRows + ", Status " + imported.stats.statusRows +
                            ", Agc " + imported.stats.agcRows + ", датчиков " + imported.stats.sensorSamples + ". "
                        Triple(org.cleargps.core.GnssReplayRunner().replay(imported.decoded), true, details)
                    }
                }
                val summary = importOutcome.first
                gnssReplaySummary = if (summary.error != null) {
                    "Реплей не выполнен: " + summary.error
                } else {
                    importOutcome.third +
                        "Реплей завершён: GPS " + summary.gpsFixes + ", network " + summary.networkFixes +
                        ", GNSS-кадров " + summary.signalFrames + ", raw measurements " + summary.rawMeasurementRecords +
                        ", отсчётов датчиков " + summary.sensorSamples +
                        ", радиокадров " + summary.radioSnapshots +
                        ", радионаблюдений " + summary.radioObservations +
                        "; пиковый пересчитанный риск " + summary.peakRiskScore +
                        "/100, подтверждённых состояний " + summary.confirmedAssessments +
                        ". Пропущено строк: " + summary.skippedLines + ". " +
                        (if (importOutcome.second) "CSV обработан; завершающая запись Clear GPS не требуется."
                        else if (summary.hasFooter) "Файл завершён корректно."
                        else "Файл неполный: завершающая запись отсутствует.")
                }
            } catch (error: Exception) {
                gnssReplaySummary = "Не удалось выполнить реплей: " + (error.message ?: error.javaClass.simpleName)
            }
        }
    }
    val exportRiskFeedbackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { RiskFeedbackStore.exportJson(prefs) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(json) }
                        ?: throw IllegalStateException("Не удалось открыть файл для записи.")
                }
                Toast.makeText(context, "Обезличенная разметка риска сохранена в выбранное место. Clear GPS не отправляет файл автоматически.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, "Ошибка экспорта разметки: " + (error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
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
                Toast.makeText(context, "WiGLE CSV сохранён в выбранное место. История наблюдений включена; Clear GPS не отправляет файл автоматически.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, "Ошибка экспорта: " + (error.message ?: "неизвестная ошибка"), Toast.LENGTH_LONG).show()
            }
        }
    }
    val exportSourceCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri: Uri? ->
        val sourceId = pendingSourceExportId
        val dayKey = pendingSourceExportDay
        val radioType = pendingSourceExportType
        pendingSourceExportId = null
        pendingSourceExportDay = null
        pendingSourceExportType = null
        pendingSourceExportFileName = null
        if (uri != null && sourceId != null) scope.launch {
            try {
                val csv = withContext(Dispatchers.IO) { database.exportWigleCsv(sourceId, dayKey, radioType) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(csv) }
                        ?: throw IllegalStateException("Не удалось открыть файл для записи.")
                }
                Toast.makeText(context, "CSV выбранного источника сохранён в выбранное место. Clear GPS не отправляет файл автоматически.", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(context, "Ошибка экспорта источника: " + (error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
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
                Toast.makeText(context, "SQLite-резервная копия Clear GPS сохранена в выбранное место. Clear GPS не отправляет файл автоматически.", Toast.LENGTH_LONG).show()
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
                Toast.makeText(context, "WiGLE SQLite сохранён в выбранное место: сети и геолокированные наблюдения. Clear GPS не отправляет файл автоматически.", Toast.LENGTH_LONG).show()
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
                    if (isSqlite) database.importSqliteDatabase(buffered) else database.importRadioCsv(buffered)
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

    val exportSettingsZoneBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                val snapshot = SettingsZoneBackupSnapshot(
                    positionMode = positionMode.key,
                    mapTheme = mapThemeMode.key,
                    mapSource = mapSourceMode.key,
                    scanIntervalSeconds = scanIntervalSeconds,
                    adaptiveScan = adaptiveScan,
                    batteryProfile = batteryProfile.key,
                    useWifi = useWifi,
                    useCells = useCells,
                    useBle = useBle,
                    zones = zones.toList()
                )
                val text = withContext(Dispatchers.Default) { SettingsZoneBackupCodec.encode(snapshot) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(text)
                    } ?: throw IllegalStateException("Не удалось открыть файл для записи.")
                }
                Toast.makeText(
                    context,
                    "Настройки и зоны экспортированы в выбранный файл. Отправки в сеть нет.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    "Ошибка экспорта настроек: " + (error.message ?: error.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    val importSettingsZoneBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) scope.launch {
            try {
                if (GnssReplayRecorder.isRecording(context)) {
                    throw IllegalStateException("Сначала останови запись GNSS replay, затем импортируй настройки.")
                }
                val text = withContext(Dispatchers.IO) {
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Не удалось открыть файл.")
                    SettingsZoneBackupCodec.readBounded(input)
                }
                val snapshot = withContext(Dispatchers.Default) { SettingsZoneBackupCodec.decode(text) }
                val importedMode = PositionMode.values().firstOrNull { it.key == snapshot.positionMode }
                    ?: throw IllegalArgumentException("Неподдерживаемый режим положения.")
                withContext(Dispatchers.IO) {
                    prefs.edit()
                        .putString(ClearGpsPreferences.KEY_MAP_STYLE, snapshot.mapTheme)
                        .putString(ClearGpsPreferences.KEY_MAP_SOURCE, snapshot.mapSource)
                        .putInt(ClearGpsPreferences.KEY_SCAN_INTERVAL_SECONDS, snapshot.scanIntervalSeconds)
                        .putBoolean(ClearGpsPreferences.KEY_ADAPTIVE_SCAN, snapshot.adaptiveScan)
                        .putString(ClearGpsPreferences.KEY_BATTERY_PROFILE, snapshot.batteryProfile)
                        .putBoolean(ClearGpsPreferences.KEY_USE_WIFI, snapshot.useWifi)
                        .putBoolean(ClearGpsPreferences.KEY_USE_CELLS, snapshot.useCells)
                        .putBoolean(ClearGpsPreferences.KEY_USE_BLE, snapshot.useBle)
                        .apply()
                    ZoneStore.saveAll(context, snapshot.zones)
                }
                mapThemeMode = MapThemeMode.values().first { it.key == snapshot.mapTheme }
                mapSourceMode = MapSourceMode.values().first { it.key == snapshot.mapSource }
                scanIntervalSeconds = snapshot.scanIntervalSeconds
                adaptiveScan = snapshot.adaptiveScan
                batteryProfile = BatteryProfile.fromKey(snapshot.batteryProfile)
                useWifi = snapshot.useWifi
                useCells = snapshot.useCells
                useBle = snapshot.useBle
                zones = ZoneStore.loadAll(context)
                mapController.updateZones(zones, draftZone)
                applyPositionMode(importedMode)
                Toast.makeText(
                    context,
                    "Настройки и зоны восстановлены. База радио, разрешение BeaconDB, мониторинг и mock-флаги не импортировались.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    "Ошибка импорта настроек: " + (error.message ?: error.javaClass.simpleName),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    suspend fun loadPairedObdAdapters() {
        try {
            val pairedClassic = obd2Repository.pairedAdapters()
            val previousNonClassicCandidates = obd2Adapters.filter {
                it.transportType != Obd2TransportType.CLASSIC_SPP
            }
            val savedWifiCandidate = selectedObd2Address
                .takeIf { it.startsWith("wifi:", ignoreCase = true) }
                ?.let { key -> runCatching { Obd2AdapterSelection.parse(key) }.getOrNull() }
                ?.takeIf { it.transportType == Obd2TransportType.WIFI_TCP }
                ?.let { Obd2Adapter("Wi-Fi OBD-II TCP", it.address, Obd2TransportType.WIFI_TCP, it.port) }
            val available = (pairedClassic + previousNonClassicCandidates + listOfNotNull(savedWifiCandidate))
                .distinctBy { it.selectionKey }
            obd2Adapters = available
            val selectionIsManualEndpoint = selectedObd2Address.startsWith("ble:", ignoreCase = true) ||
                selectedObd2Address.startsWith("wifi:", ignoreCase = true)
            if (available.none { it.selectionKey.equals(selectedObd2Address, ignoreCase = true) } &&
                (!selectionIsManualEndpoint || selectedObd2Address.isBlank())
            ) {
                selectedObd2Address = available.firstOrNull()?.selectionKey.orEmpty()
                prefs.edit().putString(Obd2SharedState.KEY_ADAPTER_ADDRESS, selectedObd2Address).apply()
            }
            val previousFailureCanNowBeCleared =
                obd2Status.startsWith("Не найден сопряжённый OBD-II адаптер.") ||
                    obd2Status.startsWith("Без разрешения «Устройства поблизости»")
            when {
                available.isEmpty() -> {
                    obd2StatusIsError = false
                    obd2Status = "Сопряжённых Classic SPP-адаптеров не найдено. Можно выполнить BLE-поиск или вручную добавить Wi-Fi OBD. GPS/радиопозиционирование работает независимо."
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
                    obd2Status = "Classic SPP: " + pairedClassic.size + "; BLE-кандидатов в текущем списке: " +
                        previousNonClassicCandidates.count { it.transportType == Obd2TransportType.BLE_GATT } + ". BLE-поиск выполняется только вручную."
                }
            }
        } catch (error: Exception) {
            obd2Adapters = obd2Adapters.filter { it.transportType != Obd2TransportType.CLASSIC_SPP }
            obd2StatusIsError = true
            obd2Status = (error.message ?: "Не удалось получить список Bluetooth-устройств.") +
                " Пробуем резервный вариант: позиционирование продолжается по выбранному GPS/радио источнику."
        }
    }

    suspend fun loadBleObdAdapters() {
        obd2BleScanning = true
        try {
            val found = obd2Repository.scanBleAdapters()
            val classic = obd2Adapters.filter { it.transportType == Obd2TransportType.CLASSIC_SPP }
            obd2Adapters = (classic + found).distinctBy { it.selectionKey }
            if (found.isNotEmpty() && obd2Adapters.none {
                it.selectionKey.equals(selectedObd2Address, ignoreCase = true)
            }) {
                selectedObd2Address = found.first().selectionKey
                prefs.edit().putString(Obd2SharedState.KEY_ADAPTER_ADDRESS, selectedObd2Address).apply()
            }
            obd2StatusIsError = false
            obd2Status = if (found.isEmpty()) {
                "BLE-поиск завершён: подходящие по имени OBD/ELM-устройства не найдены. Некоторые клоны не рекламируют распознаваемое имя; Classic SPP доступен отдельно."
            } else {
                "Найдено BLE-кандидатов: " + found.size + ". Выбери устройство и подключись; совместимость подтверждается только успешным чтением."
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            obd2StatusIsError = true
            obd2Status = (error.message ?: "Не удалось выполнить BLE-поиск.") +
                " BLE-идентификаторы не сохранены и не отправлены в сеть."
        } finally {
            obd2BleScanning = false
        }
    }

    val obd2PermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val connectMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        val scanMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
        val locationMissingForLegacyScan = Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        when {
            connectMissing -> {
                pendingObdBleScan = false
                obd2StatusIsError = true
                obd2Status = "Без разрешения «Устройства поблизости» нельзя подключиться к Bluetooth-адаптеру. GPS/радио продолжает работать."
            }
            pendingObdBleScan && (scanMissing || locationMissingForLegacyScan) -> {
                pendingObdBleScan = false
                obd2StatusIsError = true
                obd2Status = "Для BLE-поиска требуется разрешение сканирования Bluetooth (на старых версиях Android — геолокации)."
            }
            pendingObdBleScan -> {
                pendingObdBleScan = false
                scope.launch { loadBleObdAdapters() }
            }
            else -> scope.launch { loadPairedObdAdapters() }
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

    val scanBleObdAdapters: () -> Unit = {
        if (obd2Connected || obd2Connecting || obd2BleScanning) {
            obd2Status = "Сначала отключи текущий адаптер, чтобы выполнять BLE-поиск."
        } else {
            val requested = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    requested += Manifest.permission.BLUETOOTH_SCAN
                }
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    requested += Manifest.permission.BLUETOOTH_CONNECT
                }
            } else if (
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
            ) {
                requested += Manifest.permission.ACCESS_FINE_LOCATION
            }
            if (requested.isNotEmpty()) {
                pendingObdBleScan = true
                obd2PermissionLauncher.launch(requested.toTypedArray())
            } else {
                scope.launch { loadBleObdAdapters() }
            }
        }
    }

    val addWifiObdAdapter: (String, String) -> Unit = { hostInput, portInput ->
        try {
            val selection = Obd2AdapterSelection.parse("wifi:${hostInput.trim()}:${portInput.trim()}")
            val adapter = Obd2Adapter(
                name = "Wi-Fi OBD-II TCP",
                address = selection.address,
                transportType = Obd2TransportType.WIFI_TCP,
                port = selection.port
            )
            obd2Adapters = (obd2Adapters.filterNot {
                it.selectionKey.equals(selection.key, ignoreCase = true)
            } + adapter).distinctBy { it.selectionKey }
            selectedObd2Address = selection.key
            prefs.edit().putString(Obd2SharedState.KEY_ADAPTER_ADDRESS, selection.key).apply()
            obd2StatusIsError = false
            obd2Status = "Wi-Fi OBD добавлен: ${selection.address}:${selection.port}. Подключись к Wi-Fi сети адаптера в настройках Android, затем нажми «Подключить и читать параметры»."
        } catch (error: Exception) {
            obd2StatusIsError = true
            obd2Status = error.message ?: "Некорректный адрес Wi-Fi OBD."
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == AppTab.OBD2) refreshPairedObdAdapters()
    }

    fun scheduleObdReconnect() {
        if (!obd2ConnectionRequested || obd2ReconnectJob?.isActive == true) return
        val addressToReconnect = selectedObd2Address
        if (addressToReconnect.isBlank()) {
            obd2ConnectionRequested = false
            return
        }

        obd2Connecting = true
        obd2StatusIsError = false
        obd2Status = "Связь с OBD-II потеряна. Пытаюсь восстановить подключение к тому же сопряжённому адаптеру."
        obd2ReconnectJob = scope.launch {
            var lastError: Exception? = null
            try {
                for (attemptIndex in 0 until Obd2ReconnectPolicy.MAX_ATTEMPTS) {
                    if (!obd2ConnectionRequested) return@launch
                    val waitMillis = Obd2ReconnectPolicy.delayBeforeAttempt(attemptIndex) ?: break
                    obd2StatusIsError = false
                    obd2Status = "Автоподключение ${attemptIndex + 1}/${Obd2ReconnectPolicy.MAX_ATTEMPTS} через ${waitMillis / 1_000} с…"
                    delay(waitMillis)
                    if (!obd2ConnectionRequested) return@launch

                    try {
                        val adapter = obd2Repository.connect(addressToReconnect)
                        if (!obd2ConnectionRequested) {
                            obd2Repository.disconnect()
                            return@launch
                        }
                        selectedObd2Address = adapter.selectionKey
                        prefs.edit().putString(Obd2SharedState.KEY_ADAPTER_ADDRESS, adapter.selectionKey).apply()
                        Obd2SharedState.clearSpeed(prefs)
                        obd2SpeedCalibration.reset()
                        obd2CalibrationSnapshot = Obd2SpeedCalibrationSnapshot()
                        obd2GearRatioLearner.reset()
                        obd2GearLearningSnapshot = Obd2GearLearningSnapshot()
                        obd2DriveMetricsTracker.reset()
                        obd2DriveMetricsSnapshot = Obd2DriveMetricsSnapshot()
                        obd2Telemetry = Obd2Telemetry()
                        obd2Connected = true
                        obd2StatusIsError = false
                        obd2Status = "Связь с OBD-II восстановлена. Проверяю свежие данные; позиционирование продолжает работать независимо."
                        return@launch
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        lastError = error
                        obd2Repository.disconnect()
                        if (!obd2ConnectionRequested) return@launch
                        if (!Obd2ReconnectPolicy.shouldRetry(error)) {
                            obd2ConnectionRequested = false
                            obd2StatusIsError = true
                            obd2Status = "Автопереподключение остановлено: " +
                                (error.message ?: error.javaClass.simpleName) +
                                ". Проверь Bluetooth и разрешение «Устройства поблизости». GPS/радио продолжает работать."
                            return@launch
                        }
                        if (attemptIndex < Obd2ReconnectPolicy.MAX_ATTEMPTS - 1) {
                            obd2StatusIsError = false
                            obd2Status = "Попытка ${attemptIndex + 1}/${Obd2ReconnectPolicy.MAX_ATTEMPTS} не удалась. " +
                                "Повторяю подключение к выбранному адаптеру."
                        }
                    }
                }
                if (obd2ConnectionRequested && !obd2Connected) {
                    obd2ConnectionRequested = false
                    obd2StatusIsError = true
                    obd2Status = "Не удалось восстановить связь после ${Obd2ReconnectPolicy.MAX_ATTEMPTS} попыток" +
                        (lastError?.message?.let { ": $it" } ?: "") +
                        ". Нажми «Подключить и читать параметры», чтобы попробовать снова. GPS/радио продолжает работать."
                }
            } finally {
                obd2Connecting = false
                obd2ReconnectJob = null
            }
        }
    }

    LaunchedEffect(obd2Connected, obd2Telemetry, obd2ExternalBroadcastEnabled) {
        if (obd2ExternalBroadcastEnabled) {
            Obd2ExternalTelemetryBroadcast.publish(
                context = context,
                connected = obd2Connected,
                telemetry = obd2Telemetry.takeIf { it.updatedAt > 0L }
            )
        }
    }

    LaunchedEffect(obd2Connected) {
        if (!obd2Connected) {
            obd2SpeedCalibration.reset()
            obd2CalibrationSnapshot = Obd2SpeedCalibrationSnapshot()
            obd2GearRatioLearner.reset()
            obd2GearLearningSnapshot = Obd2GearLearningSnapshot()
            obd2DriveMetricsTracker.reset()
            obd2DriveMetricsSnapshot = Obd2DriveMetricsSnapshot()
            Obd2SharedState.setSpeedCorrectionScale(prefs, null)
        }
    }

    LaunchedEffect(obd2Connected, obd2Telemetry) {
        if (!obd2Connected) {
            obd2DriveMetricsTracker.reset()
            obd2DriveMetricsSnapshot = Obd2DriveMetricsSnapshot()
            return@LaunchedEffect
        }
        obd2DriveMetricsSnapshot = obd2DriveMetricsTracker.observe(
            speedKmh = Obd2SharedState.freshSpeedKmh(prefs),
            speedSampleElapsedRealtimeNanos = obd2Telemetry.speedSampleElapsedRealtimeNanos,
            nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        )
    }

    LaunchedEffect(obd2Connected, obd2Telemetry, liveGnssLocation, filterState) {
        if (!obd2Connected) return@LaunchedEffect
        val gps = liveGnssLocation
        val speedAccuracy = if (
            gps != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            gps.hasSpeedAccuracy()
        ) gps.speedAccuracyMetersPerSecond.toDouble() else null
        val calibration = obd2SpeedCalibration.observe(
            gnssSpeedKmh = gps?.takeIf { it.hasSpeed() }?.speed?.toDouble()?.times(3.6),
            gnssAccuracyMeters = gps?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
            gnssSpeedAccuracyMetersPerSecond = speedAccuracy,
            gnssElapsedRealtimeNanos = gps?.elapsedRealtimeNanos ?: 0L,
            gnssProvider = gps?.provider,
            gnssIsMock = gps == null || isMockLocation(gps),
            gnssTrustedNormal = filterState == FilterState.NORMAL.name,
            obdSpeedKmh = obd2Telemetry.vehicleSpeedKmh,
            obdSampleElapsedRealtimeNanos = obd2Telemetry.speedSampleElapsedRealtimeNanos,
            obdQueryDurationMillis = obd2Telemetry.speedQueryDurationMillis,
            nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        )
        obd2CalibrationSnapshot = calibration
        obd2GearLearningSnapshot = obd2GearRatioLearner.observe(
            engineRpm = obd2Telemetry.engineRpm,
            vehicleSpeedKmh = obd2Telemetry.vehicleSpeedKmh,
            engineLoadPercent = obd2Telemetry.engineLoadPercent,
            throttlePercent = obd2Telemetry.throttlePercent,
            engineRpmSampleElapsedRealtimeNanos = obd2Telemetry.engineRpmSampleElapsedRealtimeNanos,
            engineRpmQueryDurationMillis = obd2Telemetry.engineRpmQueryDurationMillis,
            speedSampleElapsedRealtimeNanos = obd2Telemetry.speedSampleElapsedRealtimeNanos,
            speedQueryDurationMillis = obd2Telemetry.speedQueryDurationMillis,
            trustedSpeedPair = calibration.differenceKmh != null &&
                calibration.status in setOf(
                    Obd2CalibrationStatus.COLLECTING,
                    Obd2CalibrationStatus.CALIBRATED
                ),
            nowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        )
        calibration.activeCorrectionScale?.let { scale ->
            Obd2SharedState.setSpeedCorrectionScale(prefs, scale)
        }
    }

    LaunchedEffect(obd2Connected) {
        if (obd2Connected) {
            while (true) {
                try {
                    val latest = obd2Repository.readTelemetry()
                    obd2Telemetry = latest
                    Obd2SharedState.storeTelemetry(prefs, latest)
                    when {
                        latest.speedIssue == Obd2SpeedIssue.TRANSIENT_ZERO_SUPPRESSED -> {
                            obd2StatusIsError = false
                            obd2Status = "Нулевой ответ PID 0D пока не подтверждён. Ноль появится в данных после трёх последовательных нулевых измерений; GPS/радио продолжают работать независимо."
                        }
                        latest.vehicleSpeedKmh != null -> {
                            obd2StatusIsError = false
                            obd2Status = "Скорость получена по стандартному PID 0D. OBD-II используется только как вспомогательный источник; GPS/радио сохраняет приоритет выбранного режима."
                        }
                        latest.speedIssue == Obd2SpeedIssue.PID_UNSUPPORTED -> {
                            obd2StatusIsError = true
                            obd2Status = "ЭБУ сообщил, что PID 0D не поддерживается. Пробуем резервный вариант: продолжаем позиционирование по GPS/радиобазе без автомобильной скорости; неподдерживаемый PID не опрашиваем."
                        }
                        latest.speedIssue == Obd2SpeedIssue.PID_DISABLED_FOR_SESSION -> {
                            obd2StatusIsError = true
                            obd2Status = "Три подряд некорректных ответа PID 0D. Этот PID отключён до переподключения; старые данные очищены, GPS/радиопозиционирование продолжает работать."
                        }
                        else -> {
                            obd2StatusIsError = true
                            obd2Status = "Не удалось получить корректную скорость через PID 0D. Пробуем резервный вариант: позиционирование продолжается по выбранному GPS/радио источнику без старой скорости. Повторяем безопасное чтение; недостоверное значение отброшено."
                        }
                    }
                    delay(Obd2PollingPolicy.nextDelayMillis(latest))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!obd2Connected) break
                    val failureMessage = error.message ?: "Связь с OBD-II адаптером потеряна."
                    obd2Connected = false
                    obd2Telemetry = Obd2Telemetry()
                    Obd2SharedState.clearSpeed(prefs)
                    obd2Repository.disconnect()
                    if (obd2ConnectionRequested) {
                        obd2StatusIsError = false
                        obd2Status = failureMessage +
                            " Старые данные очищены. Пробую автоматически восстановить связь с тем же адаптером; GPS/радио продолжает работать."
                        scheduleObdReconnect()
                    } else {
                        obd2StatusIsError = true
                        obd2Status = failureMessage +
                            " Старые данные очищены. GPS/радиопозиционирование продолжает работать; при необходимости подключи адаптер вручную."
                    }
                    break
                }
            }
        }
    }

    DisposableEffect(obd2Repository) {
        onDispose {
            obd2ReconnectJob?.cancel()
            obd2ConnectJob?.cancel()
            obd2Repository.close()
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
        if (positionMode.usesRadio || positionMode.gpsOnly) {
            liveLocation = null
            liveGnssLocation = null
        }
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
                    if (location.provider.equals(LocationManager.GPS_PROVIDER, true)) {
                        val oldGnss = liveGnssLocation
                        if (oldGnss == null || isBetterLocation(location, oldGnss)) {
                            liveGnssLocation = Location(location)
                        }
                    }
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
                            provider.equals(LocationManager.GPS_PROVIDER, true) &&
                            (liveGnssLocation == null || isBetterLocation(cached, liveGnssLocation!!))) {
                            liveGnssLocation = Location(cached)
                        }
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

    LaunchedEffect(recording, appVisible, hasAnyLocationPermission(context), positionMode, scanIntervalSeconds, adaptiveScan, batteryProfile, monitorEnabled) {
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
                delay(effectiveScanIntervalSeconds(scanIntervalSeconds, speedKmh, adaptiveScan, batteryProfile) * 1_000L)
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
            riskEventSource = prefs.getString(LocationMonitorService.KEY_SOURCE, "NONE") ?: "NONE"
            riskFeedbackCount = RiskFeedbackStore.count(prefs)
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
            gnssReplayRecording = GnssReplayRecorder.isRecording(context)
            gnssReplayBytes = GnssReplayRecorder.fileSize(context)
            gnssReplayStopReason = GnssReplayRecorder.lastStopReason(context)

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

    val fusionNowElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    val freshObdSpeedSample = Obd2SharedState.freshSpeedSample(prefs, fusionNowElapsedRealtimeNanos)
    val freshObdSpeedKmh = freshObdSpeedSample?.speedKmh
    val maxLiveAgeMillis = if (freshObdSpeedKmh != null) 30_000L else 15 * 60 * 1_000L
    val currentFilterState = FilterState.values().firstOrNull { it.name == filterState } ?: FilterState.WAITING
    val currentRiskZone = prefs.getBoolean(LocationMonitorService.KEY_IN_ZONE, false)
    val gpsCoordinateHeld = PositionPublicationPolicy.shouldHideLiveGpsCoordinate(
        state = currentFilterState,
        riskScore = riskScore,
        inRiskZone = currentRiskZone
    )
    val mockCoordinateHeld = PositionPublicationPolicy.shouldHoldGpsBackedOutput(
        state = currentFilterState,
        riskScore = riskScore,
        inRiskZone = currentRiskZone,
        source = riskEventSource
    )
    val validLive = liveLocation?.takeIf { System.currentTimeMillis() - it.time in 0..maxLiveAgeMillis }
    val activeLocation = when (positionMode) {
        PositionMode.GPS_ONLY ->
            validLive?.takeIf { it.provider.equals(LocationManager.GPS_PROVIDER, true) && !gpsCoordinateHeld }
        PositionMode.AUTO -> validLive?.takeIf {
            !it.provider.equals(LocationManager.GPS_PROVIDER, true) || !gpsCoordinateHeld
        }
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
    val speedHistoryForMap = activeLocation?.takeIf {
        it.provider.equals(LocationManager.GPS_PROVIDER, true)
    }?.let {
        Obd2SharedState.speedSamplesSince(it.elapsedRealtimeNanos, fusionNowElapsedRealtimeNanos)
    }.orEmpty()
    val predictedGpsPosition = if (
        activeLocation != null &&
        activeLocation.provider.equals(LocationManager.GPS_PROVIDER, true) &&
        freshObdSpeedKmh != null
    ) {
        Obd2LocationFusion.predictFromGps(
            lastFix = MapCoordinate(activeLocation.latitude, activeLocation.longitude),
            baseAccuracyMeters = activeLocation.takeIf { it.hasAccuracy() }?.accuracy?.toDouble(),
            bearingDegrees = activeLocation.takeIf { it.hasBearing() }?.bearing?.toDouble(),
            fixElapsedRealtimeNanos = activeLocation.elapsedRealtimeNanos,
            speedSamples = speedHistoryForMap,
            nowElapsedRealtimeNanos = fusionNowElapsedRealtimeNanos
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
        mockOutputActive && publishedMockCoordinate != null && !mockCoordinateHeld -> publishedMockCoordinate
        positionMode.usesRadio -> radioCoordinate
        else -> primaryCoordinate ?: manualAnchor?.let { MapCoordinate(it.latitude, it.longitude) }
    }
    val displayAccuracy = when {
        mockOutputActive && publishedMockCoordinate != null && !mockCoordinateHeld -> publishedMockAccuracy ?: primaryAccuracy
        positionMode.usesRadio -> radioAccuracy
        else -> primaryAccuracy
    }
    val radioEstimateAgeMillis = currentRadioEstimate?.let { System.currentTimeMillis() - it.updatedAt } ?: Long.MAX_VALUE
    val radioEstimateStale = publishedMockCoordinate == null && currentRadioEstimate != null &&
        radioEstimateAgeMillis > maxOf(15_000L, scanIntervalSeconds * 2_000L)

    val primarySource = when {
        mockOutputActive && publishedMockCoordinate != null && !mockCoordinateHeld &&
            serviceStatus.contains("BeaconDB", ignoreCase = true) ->
            "BeaconDB · временная публикация mock GPS"
        positionMode.usesRadio && publishedMockCoordinate != null && !mockCoordinateHeld ->
            "Mock GPS · опубликованная радиопозиция"
        gpsCoordinateHeld && activeLocation == null && !positionMode.usesRadio ->
            "GNSS-координата удерживается фильтром доверия"
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
        mockOutputActive && publishedMockCoordinate != null && !mockCoordinateHeld -> publishedMockCoordinate
        else -> predictedGpsPosition?.coordinate ?: measuredCoordinate
    }
    val mapPrimaryAccuracy = when {
        mockOutputActive && publishedMockCoordinate != null && !mockCoordinateHeld -> publishedMockAccuracy ?: primaryAccuracy
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
    LaunchedEffect(selectedTab, mapTapMode) {
        if (selectedTab == AppTab.MAP) mapController.setTapMode(mapTapMode)
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

    fun needsLocationPermissionForCurrentMode(): Boolean =
        !hasAnyLocationPermission(context) ||
            (positionMode.gpsOnly && !hasFineLocationPermission(context))

    val runMonitoring: () -> Unit = {
        pendingMockEnable = false
        if (needsLocationPermissionForCurrentMode()) {
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
        if (needsLocationPermissionForCurrentMode()) {
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
        if (needsLocationPermissionForCurrentMode()) {
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
            showSourceExportWarning -> {
                showSourceExportWarning = false
                pendingSourceExportId = null
                pendingSourceExportDay = null
                pendingSourceExportType = null
                pendingSourceExportFileName = null
            }
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
            val panelMaxHeight = if (selectedTab == AppTab.MAP) {
                maxHeight * panelFraction.value
            } else {
                (maxHeight - WindowInsets.statusBars.asPaddingValues().calculateTopPadding() - 75.dp).coerceAtLeast(0.dp)
            }
            val panelBottomOffset = if (panelVisible && selectedTab == AppTab.MAP) panelMaxHeight else 0.dp
            val panelHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
            if (selectedTab == AppTab.MAP) {
                AndroidView(
                    factory = { viewContext ->
                        MapView(viewContext).also { view ->
                            onMapViewCreated(view)
                            mapController.attach(view, viewContext, mapIsDark, mapSourceMode == MapSourceMode.OFFLINE)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { view ->
                        mapController.detach(view)
                        onMapViewDisposed(view)
                    }
                )
            }

            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Column(
                    Modifier.fillMaxWidth()
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 7.dp)
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeaderCard(
                        tab = selectedTab,
                        mode = positionMode,
                        monitorEnabled = monitorEnabled,
                        filterState = filterState,
                        riskScore = riskScore,
                        onCenter = { mapController.focusPrimary() },
                        onSettings = { navigateToTab(AppTab.SETTINGS) }
                    )
                    if (selectedTab == AppTab.MAP && mapTapMode != MapTapMode.NONE) {
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
                    visible = panelVisible || selectedTab != AppTab.MAP,
                    modifier = Modifier.fillMaxWidth(),
                    enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(190)) +
                        fadeIn(animationSpec = tween(140)),
                    exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(170)) +
                        fadeOut(animationSpec = tween(120))
                ) {
                Surface(
                    Modifier.fillMaxWidth().height(panelMaxHeight),
                    shape = if (selectedTab == AppTab.MAP) {
                        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    } else {
                        RoundedCornerShape(0.dp)
                    },
                    color = colors.surfaceContainer, tonalElevation = 4.dp, shadowElevation = 12.dp
                ) {
                    Column(Modifier.fillMaxSize()) {
                        if (selectedTab == AppTab.MAP) {
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
                                    riskEventSource = riskEventSource,
                                    riskFeedbackCount = riskFeedbackCount,
                                    onRiskFeedback = { label ->
                                        val matchingBreakdown = riskBreakdown?.takeIf { it.finalScore == riskScore }
                                        val record = RiskFeedbackRecord(
                                            timestampEpochMillis = System.currentTimeMillis(),
                                            label = label,
                                            filterState = RiskFeedbackCodec.normalizeState(filterState),
                                            riskScore = riskScore.coerceIn(0, 100),
                                            sourceCode = RiskFeedbackCodec.normalizeSource(riskEventSource),
                                            motionScore = matchingBreakdown?.takeIf { it.motionEvaluated }?.motionScore,
                                            crossSourceScore = matchingBreakdown?.takeIf { it.crossSourceEvaluated }?.crossSourceScore,
                                            gnssSignalScore = matchingBreakdown?.takeIf { it.gnssSignalEvaluated }?.gnssSignalScore,
                                            inRiskZone = prefs.getBoolean(LocationMonitorService.KEY_IN_ZONE, false),
                                            temporalHoldFloorApplied = matchingBreakdown?.temporalHoldFloorApplied == true,
                                            reasonCodes = RiskFeedbackCodec.classifyReasons(
                                                riskReasons, riskEventSource, matchingBreakdown
                                            )
                                        )
                                        RiskFeedbackStore.append(prefs, record)
                                        riskFeedbackCount = RiskFeedbackStore.count(prefs)
                                        Toast.makeText(context, "Метка сохранена локально. Она не меняет детектор.", Toast.LENGTH_SHORT).show()
                                    },
                                    onExportRiskFeedback = {
                                        exportRiskFeedbackLauncher.launch("clear-gps-risk-feedback.json")
                                    },
                                    onClearRiskFeedback = {
                                        RiskFeedbackStore.clear(prefs)
                                        riskFeedbackCount = 0
                                        Toast.makeText(context, "Локальная разметка очищена.", Toast.LENGTH_SHORT).show()
                                    },
                                    replayRecording = gnssReplayRecording,
                                    replayBytes = gnssReplayBytes,
                                    replayStopReason = gnssReplayStopReason,
                                    replaySaved = GnssReplayRecorder.hasSavedLog(context),
                                    replayCanStart = GnssReplayRecorder.canStart(context),
                                    replaySummary = gnssReplaySummary,
                                    onStartReplayRecord = {
                                        val ok = GnssReplayRecorder.start(context)
                                        if (ok) LocationMonitorService.notifyReplayRecordingChanged(context)
                                        gnssReplayRecording = GnssReplayRecorder.isRecording(context)
                                        gnssReplayBytes = GnssReplayRecorder.fileSize(context)
                                        gnssReplayStopReason = GnssReplayRecorder.lastStopReason(context)
                                        if (!ok) Toast.makeText(context,
                                            "Не удалось начать запись. Проверь режим, мониторинг и наличие старого replay-файла.",
                                            Toast.LENGTH_LONG).show()
                                    },
                                    onStopReplayRecord = {
                                        GnssReplayRecorder.stop(context, "USER_STOP")
                                        LocationMonitorService.notifyReplayRecordingChanged(context)
                                        gnssReplayRecording = false
                                        gnssReplayBytes = GnssReplayRecorder.fileSize(context)
                                        gnssReplayStopReason = GnssReplayRecorder.lastStopReason(context)
                                    },
                                    onExportReplay = { exportGnssReplayLauncher.launch("clear-gps-gnss-replay.cgreplay") },
                                    onOpenReplay = { openGnssReplayLauncher.launch(arrayOf("application/octet-stream","text/plain","*/*")) },
                                    onDeleteReplay = {
                                        GnssReplayRecorder.clear(context)
                                        gnssReplayBytes = 0L
                                        gnssReplayStopReason = ""
                                        gnssReplaySummary = ""
                                    },
                                    gnssEnvironment = gnssEnvironment, gnssEnvironmentReasons = gnssEnvironmentReasons,
                                    gnssBaselineReady = gnssBaselineReady, gnssBaselineDeviations = gnssBaselineDeviations,
                                    gnssDiagnosticsAtNanos = gnssDiagnosticsAtNanos, gnssDiagnosticsNowNanos = gnssDiagnosticsNowNanos,
                                    gnssLiveSnapshot = gnssLiveSnapshot,
                                    onMode = { requestedMode -> selectPositionMode(requestedMode) },
                                    obd2Connected = obd2Connected,
                                    onOpenObd2 = { navigateToTab(AppTab.OBD2) },
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
                                    points = points, history = history, sources = radioSources, sourceDaysBySource = sourceDaysBySource,
                                    sourceSegmentsBySource = sourceSegmentsBySource,
                                    pointCount = pointCount, observationCount = observationCount,
                                    mapPointLoadedCount = mapPoints.size,
                                    geolocatedPointCount = geolocatedMapPointCount,
                                    onSetSourceEnabled = { source, enabled ->
                                        scope.launch {
                                            try {
                                                val changed = withContext(Dispatchers.IO) { database.setRadioSourceEnabled(source.id, enabled) }
                                                refreshLocalUiInCurrentCoroutine()
                                                if (!changed) Toast.makeText(context, "Этот источник нельзя выключить.", Toast.LENGTH_SHORT).show()
                                            } catch (error: Exception) {
                                                Toast.makeText(context, "Не удалось изменить источник: " + (error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    onSetSourceTrustWeight = { source, weight ->
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) { database.setRadioSourceTrustWeight(source.id, weight) }
                                                refreshLocalUiInCurrentCoroutine()
                                            } catch (error: Exception) {
                                                Toast.makeText(context, "Не удалось изменить доверие: " + (error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    onDeleteSource = { source ->
                                        scope.launch {
                                            try {
                                                val deleted = withContext(Dispatchers.IO) { database.deleteRadioSource(source.id) }
                                                refreshLocalUiInCurrentCoroutine()
                                                Toast.makeText(
                                                    context,
                                                    if (deleted) "Источник удалён, затронутые координаты пересчитаны." else "Источник не найден или защищён.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } catch (error: Exception) {
                                                Toast.makeText(context, "Не удалось удалить источник: " + (error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    onExportSource = { source ->
                                        pendingSourceExportId = source.id
                                        pendingSourceExportDay = null
                                        pendingSourceExportType = null
                                        pendingSourceExportFileName = "ClearGPS-source-" + source.id + ".csv"
                                        showSourceExportWarning = true
                                    },
                                    onExportSourceDay = { source, day ->
                                        pendingSourceExportId = source.id
                                        pendingSourceExportDay = day.dayKey
                                        pendingSourceExportType = null
                                        pendingSourceExportFileName = "ClearGPS-source-" + source.id + "-" + day.dayKey + ".csv"
                                        showSourceExportWarning = true
                                    },
                                    onDeleteSourceDay = { day ->
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        scope.launch {
                                            try {
                                                val result = withContext(Dispatchers.IO) {
                                                    database.deleteRadioSourceDay(day.sourceId, day.dayKey)
                                                }
                                                refreshLocalUiInCurrentCoroutine()
                                                showRadioDeletionUndoSnackbar(
                                                    "Удалено наблюдений: " + result.observationsDeleted +
                                                        "; пересчитано радиоточек: " + result.radiosRecalculated +
                                                        "; удалено сканирований: " + result.scanHistoryDeleted,
                                                    result.undoToken
                                                )
                                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                snackbarHostState.showSnackbar(
                                                    "Не удалось удалить день: " + (error.message ?: error.javaClass.simpleName),
                                                    withDismissAction = true,
                                                    duration = SnackbarDuration.Short
                                                )
                                            }
                                        }
                                    },
                                    onSetSourceSegmentEnabled = { segment, enabled ->
                                        scope.launch {
                                            try {
                                                val changed = withContext(Dispatchers.IO) {
                                                    database.setRadioSourceSegmentEnabled(segment.sourceId, segment.dayKey, segment.radioType, enabled)
                                                }
                                                refreshLocalUiInCurrentCoroutine()
                                                if (!changed) Toast.makeText(context, "Запись источника не найдена.", Toast.LENGTH_SHORT).show()
                                            } catch (error: Exception) {
                                                Toast.makeText(context, "Не удалось изменить запись: " + (error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    onSetSourceSegmentTrustWeight = { segment, weight ->
                                        scope.launch {
                                            try {
                                                val changed = withContext(Dispatchers.IO) {
                                                    database.setRadioSourceSegmentTrustWeight(segment.sourceId, segment.dayKey, segment.radioType, weight)
                                                }
                                                refreshLocalUiInCurrentCoroutine()
                                                if (!changed) Toast.makeText(context, "Запись источника не найдена.", Toast.LENGTH_SHORT).show()
                                            } catch (error: Exception) {
                                                Toast.makeText(context, "Не удалось изменить доверие записи.", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    onDeleteSourceSegment = { segment ->
                                        snackbarHostState.currentSnackbarData?.dismiss()
                                        scope.launch {
                                            try {
                                                val result = withContext(Dispatchers.IO) {
                                                    database.deleteRadioSourceSegment(segment.sourceId, segment.dayKey, segment.radioType)
                                                }
                                                refreshLocalUiInCurrentCoroutine()
                                                showRadioDeletionUndoSnackbar(
                                                    "Удалено наблюдений: " + result.observationsDeleted +
                                                        "; пересчитано радиоточек: " + result.radiosRecalculated,
                                                    result.undoToken
                                                )
                                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                throw cancelled
                                            } catch (error: Exception) {
                                                snackbarHostState.showSnackbar(
                                                    "Не удалось удалить запись: " + (error.message ?: error.javaClass.simpleName),
                                                    withDismissAction = true,
                                                    duration = SnackbarDuration.Short
                                                )
                                            }
                                        }
                                    },
                                    onExportSourceSegment = { segment ->
                                        pendingSourceExportId = segment.sourceId
                                        pendingSourceExportDay = segment.dayKey
                                        pendingSourceExportType = segment.radioType
                                        pendingSourceExportFileName = "ClearGPS-" + segment.sourceId + "-" + segment.dayKey + "-" + segment.radioType + ".csv"
                                        showSourceExportWarning = true
                                    },
                                    observationRetentionLimit = observationRetentionLimit,
                                    retentionLimitUpdating = retentionLimitUpdating,
                                    onObservationRetentionLimit = { requestedLimit ->
                                        if (!retentionLimitUpdating && requestedLimit != observationRetentionLimit) {
                                            retentionLimitUpdating = true
                                            scope.launch {
                                                try {
                                                    val normalized = RadioDatabaseRetentionPolicy.normalizeLimit(requestedLimit)
                                                    val removed = withContext(Dispatchers.IO) {
                                                        database.setObservationRetentionLimit(normalized)
                                                    }
                                                    observationRetentionLimit = normalized
                                                    refreshLocalUiInCurrentCoroutine()
                                                    val message = if (removed > 0) {
                                                        "Лимит применён: удалено старых наблюдений $removed. Точки и координаты сохранены."
                                                    } else {
                                                        "Лимит наблюдений обновлён; удалять старые записи не пришлось."
                                                    }
                                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                                } catch (error: Exception) {
                                                    Toast.makeText(
                                                        context,
                                                        "Не удалось применить лимит: ${error.message ?: error.javaClass.simpleName}",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } finally {
                                                    retentionLimitUpdating = false
                                                }
                                            }
                                        }
                                    },
                                    searchQuery = searchQuery, onSearch = { query ->
                                        searchQuery = query
                                        scope.launch {
                                            points = withContext(Dispatchers.IO) {
                                                if (query.isBlank()) radioRepository.loadLocalPoints(limit = RADIO_POINT_LIST_LIMIT) else radioRepository.loadLocalPoints(query = query, limit = RADIO_POINT_LIST_LIMIT)
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
                                    localPositionSourceEnabled = localPositionSourceEnabled,
                                    beaconDbPositionSourceEnabled = beaconDbPositionSourceEnabled,
                                    openCellIdPositionSourceEnabled = openCellIdPositionSourceEnabled,
                                    onLocalPositionSourceEnabled = { enabled ->
                                        localPositionSourceEnabled = enabled
                                        prefs.edit().putBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_LOCAL_ENABLED, enabled).apply()
                                        if (!enabled) scanMessage = "Локальная база отключена только для расчёта позиции; данные не удалены."
                                    },
                                    onBeaconDbPositionSourceEnabled = { enabled ->
                                        beaconDbPositionSourceEnabled = enabled
                                        prefs.edit().putBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_BEACONDB_ENABLED, enabled).apply()
                                        beaconDbMessage = if (enabled) {
                                            "BeaconDB включена как источник объединённой позиции. Используется только кэш; ручной запрос по-прежнему управляется отдельным разрешением."
                                        } else {
                                            "BeaconDB исключена из объединённой позиции. Кэш и результат не удалены."
                                        }
                                    },
                                    onOpenCellIdPositionSourceEnabled = { enabled ->
                                        openCellIdPositionSourceEnabled = enabled
                                        prefs.edit().putBoolean(ClearGpsPreferences.KEY_RADIO_SOURCE_OPENCELLID_ENABLED, enabled).apply()
                                        openCellIdMessage = if (enabled) {
                                            "OpenCellID включён как источник объединённой позиции. Используется только локальный кэш вышек."
                                        } else {
                                            "OpenCellID исключён из объединённой позиции. Кэш вышек сохранён."
                                        }
                                    },
                                    beaconDbEnabled = beaconDbEnabled,
                                    beaconDbLoading = beaconDbLoading,
                                    beaconDbResult = beaconDbLookupResult,
                                    beaconDbMessage = beaconDbMessage,
                                    openCellIdRecords = openCellIdRecords,
                                    openCellIdKeyConfigured = openCellIdKeyConfigured,
                                    openCellIdKeyInput = openCellIdKeyInput,
                                    openCellIdEnabled = openCellIdEnabled,
                                    openCellIdLoading = openCellIdLoading,
                                    openCellIdMessage = openCellIdMessage,
                                    onOpenCellIdKeyInput = { openCellIdKeyInput = it },
                                    onSaveOpenCellIdKey = { saveOpenCellIdApiKey() },
                                    onForgetOpenCellIdKey = { forgetOpenCellIdApiKey() },
                                    onOpenCellIdEnabled = {
                                        openCellIdEnabled = it
                                        OpenCellIdKeyStore.setEnabled(context, it)
                                        openCellIdMessage = if (it) {
                                            "Автообновление OpenCellID разрешено: при включённом фиксе приложение проверяет новые соты и сохраняет неизвестные записи. До 3 запросов за пакет, пауза 15 минут и общий лимит 100 попыток в сутки; ручной поиск тоже доступен."
                                        } else {
                                            openCellIdRequestToken += 1
                                            openCellIdLookupJob?.cancel()
                                            openCellIdLookupJob = null
                                            openCellIdLoading = false
                                            "Запросы OpenCellID выключены."
                                        }
                                    },
                                    onOpenCellIdLookup = { startOpenCellIdLookup() },
                                    onDataSource = { source ->
                                        databaseDataSource = source
                                        prefs.edit().putString(ClearGpsPreferences.KEY_DATABASE_VIEW_SOURCE, source.key).apply()
                                        if (source != DatabaseDataSource.BEACONDB && beaconDbLookupJob?.isActive == true) {
                                            beaconDbRequestToken += 1
                                            beaconDbLookupJob?.cancel()
                                            beaconDbLookupJob = null
                                            beaconDbLoading = false
                                            beaconDbMessage = "Запрос BeaconDB отменён при переключении источника."
                                        }
                                        if (source != DatabaseDataSource.OPENCELLID && openCellIdLookupJob?.isActive == true) {
                                            openCellIdRequestToken += 1
                                            openCellIdLookupJob?.cancel()
                                            openCellIdLookupJob = null
                                            openCellIdLoading = false
                                            openCellIdMessage = "Запрос OpenCellID отменён при переключении источника."
                                        }
                                    },
                                    onBeaconDbLookup = { startBeaconDbLookup() },
                                    onPublishBeaconDbLocation = { publishBeaconDbEstimateAsMock() },
                                    onOpenBeaconDbSettings = { navigateToTab(AppTab.SETTINGS) }
                                )
                                AppTab.ZONES -> ZonesScreen(
                                    zones = zones, draftZone = draftZone, mapTapMode = mapTapMode,
                                    airportAvoidanceEnabled = airportAvoidanceEnabled,
                                    airportRadiusMeters = airportRadiusMeters,
                                    airportExclusions = airportExclusions,
                                    airportCatalogOnlineEnabled = airportCatalogOnlineEnabled,
                                    airportCatalogAvailable = airportCatalogSnapshot.isAvailable,
                                    airportCatalogStale = airportCatalogSnapshot.isStale,
                                    airportCatalogCount = airportCatalogSnapshot.airports.size,
                                    airportCatalogUpdatedAt = airportCatalogSnapshot.updatedAtMillis,
                                    airportCatalogSource = airportCatalogSnapshot.source,
                                    airportCatalogRefreshing = airportCatalogRefreshing,
                                    onAirportAvoidanceEnabled = { enabled ->
                                        airportAvoidanceEnabled = enabled
                                        prefs.edit().putBoolean(ClearGpsPreferences.KEY_AIRPORT_AVOIDANCE_ENABLED, enabled).apply()
                                    },
                                    onAirportRadiusMeters = { radius ->
                                        val normalized = radius.coerceIn(
                                            SpoofZoneService.MIN_AIRPORT_RADIUS_METERS,
                                            SpoofZoneService.MAX_AIRPORT_RADIUS_METERS
                                        )
                                        airportRadiusMeters = normalized
                                        prefs.edit().putInt(ClearGpsPreferences.KEY_AIRPORT_RADIUS_METERS, normalized).apply()
                                    },
                                    onAirportExclusions = { codes ->
                                        airportExclusions = codes.take(500)
                                        prefs.edit().putString(ClearGpsPreferences.KEY_AIRPORT_EXCLUSIONS, airportExclusions).apply()
                                    },
                                    onAirportCatalogOnlineEnabled = { enabled ->
                                        airportCatalogOnlineEnabled = enabled
                                        prefs.edit().putBoolean(ClearGpsPreferences.KEY_AIRPORT_CATALOG_ONLINE_ENABLED, enabled).apply()
                                    },
                                    onRefreshAirportCatalog = {
                                        if (!airportCatalogRefreshing) {
                                            scope.launch {
                                                airportCatalogRefreshing = true
                                                try {
                                                    val snapshot = withContext(Dispatchers.IO) {
                                                        AirportCatalogStore.refresh(context)
                                                    }
                                                    airportCatalogSnapshot = snapshot
                                                    Toast.makeText(
                                                        context,
                                                        "Каталог сохранён на устройстве: ${snapshot.airports.size} аэропортов.",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } catch (error: Exception) {
                                                    Toast.makeText(
                                                        context,
                                                        "Не удалось обновить каталог аэропортов: " +
                                                            (error.message ?: error.javaClass.simpleName),
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } finally {
                                                    airportCatalogRefreshing = false
                                                }
                                            }
                                        }
                                    },
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
                                    scanningBle = obd2BleScanning,
                                    telemetry = obd2Telemetry,
                                    calibration = obd2CalibrationSnapshot,
                                    gearLearning = obd2GearLearningSnapshot,
                                    driveMetrics = obd2DriveMetricsSnapshot,
                                    status = obd2Status,
                                    statusIsError = obd2StatusIsError,
                                    externalBroadcastEnabled = obd2ExternalBroadcastEnabled,
                                    onExternalBroadcastEnabledChange = { enabled ->
                                        if (!enabled && obd2ExternalBroadcastEnabled) {
                                            Obd2ExternalTelemetryBroadcast.publishDisabled(context)
                                        }
                                        prefs.edit()
                                            .putBoolean(Obd2ExternalTelemetryBroadcast.KEY_ENABLED, enabled)
                                            .apply()
                                        obd2ExternalBroadcastEnabled = enabled
                                    },
                                    onRefresh = { refreshPairedObdAdapters() },
                                    onScanBle = { scanBleObdAdapters() },
                                    onAddWifi = { host, port -> addWifiObdAdapter(host, port) },
                                    onSelect = {
                                        selectedObd2Address = it
                                        prefs.edit().putString(Obd2SharedState.KEY_ADAPTER_ADDRESS, it).apply()
                                    },
                                    onConnect = {
                                        if (!selectedObd2Address.startsWith("wifi:", ignoreCase = true) &&
                                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                                            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                                            obd2PermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                                        } else if (selectedObd2Address.isBlank()) {
                                            obd2StatusIsError = true
                                            obd2Status = "Сначала выбери сопряжённый адаптер. Пробуем резервный вариант: позиционирование продолжается по выбранному GPS/радио источнику."
                                        } else {
                                            obd2ReconnectJob?.cancel()
                                            obd2ReconnectJob = null
                                            obd2ConnectJob?.cancel()
                                            obd2ConnectionRequested = false
                                            val addressToConnect = selectedObd2Address
                                            obd2ConnectJob = scope.launch {
                                                obd2Connecting = true
                                                obd2StatusIsError = false
                                                obd2Status = if (addressToConnect.startsWith("wifi:", ignoreCase = true)) {
                                                    "Подключение к Wi-Fi OBD TCP…"
                                                } else {
                                                    "Подключение к Bluetooth-адаптеру…"
                                                }
                                                try {
                                                    val adapter = obd2Repository.connect(addressToConnect)
                                                    selectedObd2Address = adapter.selectionKey
                                                    prefs.edit().putString(Obd2SharedState.KEY_ADAPTER_ADDRESS, adapter.selectionKey).apply()
                                                    Obd2SharedState.clearSpeed(prefs)
                                                    obd2SpeedCalibration.reset()
                                                    obd2CalibrationSnapshot = Obd2SpeedCalibrationSnapshot()
                                                    obd2GearRatioLearner.reset()
                                                    obd2GearLearningSnapshot = Obd2GearLearningSnapshot()
                                                    obd2DriveMetricsTracker.reset()
                                                    obd2DriveMetricsSnapshot = Obd2DriveMetricsSnapshot()
                                                    obd2Telemetry = Obd2Telemetry()
                                                    obd2ConnectionRequested = true
                                                    obd2Connected = true
                                                    obd2StatusIsError = false
                                                    obd2Status = "Подключено. Проверяю карты возможностей, VIN и стандартные параметры; для позиции используется только свежая скорость PID 0D."
                                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                                    throw cancelled
                                                } catch (error: Exception) {
                                                    obd2Connected = false
                                                    obd2ConnectionRequested = false
                                                    Obd2SharedState.clearSpeed(prefs)
                                                    obd2StatusIsError = true
                                                    obd2Status = (error.message ?: "Не удалось подключиться к адаптеру.") +
                                                        " Пробуем резервный вариант: позиционирование продолжается по выбранному GPS/радио источнику."
                                                } finally {
                                                    obd2Connecting = false
                                                    obd2ConnectJob = null
                                                }
                                            }
                                        }
                                    },
                                    onDisconnect = {
                                        obd2ConnectionRequested = false
                                        obd2ReconnectJob?.cancel()
                                        obd2ReconnectJob = null
                                        obd2ConnectJob?.cancel()
                                        obd2ConnectJob = null
                                        obd2Connecting = false
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
                                    wifiSsidBlacklist = wifiSsidBlacklist,
                                    manualFallbackEnabled = manualFallbackEnabled,
                                    mapSource = mapSourceMode, scanIntervalSeconds = scanIntervalSeconds, adaptiveScan = adaptiveScan,
                                    batteryProfile = batteryProfile,
                                    publishLowAccuracy = publishLowAccuracy,
                                    onPublishLowAccuracy = { enabled ->
                                        publishLowAccuracy = enabled
                                        prefs.edit().putBoolean(LocationMonitorService.KEY_PUBLISH_LOW_ACCURACY, enabled).apply()
                                        if (monitorEnabled) {
                                            val intent = Intent(context, LocationMonitorService::class.java)
                                                .setAction(LocationMonitorService.ACTION_START)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                                            else context.startService(intent)
                                        }
                                    },
                                    databaseFacts = "Точек: " + pointCount + " · наблюдений: " + observationCount,
                                    detailedLogging = detailedLogging,
                                    diagnosticLogMaxKb = diagnosticLogMaxKb,
                                    beaconDbEnabled = beaconDbEnabled,
                                    onBeaconDbEnabled = { enabled ->
                                        prefs.edit().putBoolean(ClearGpsPreferences.KEY_BEACONDB_LOOKUP_ENABLED, enabled).apply()
                                        // Disabling future network requests does not discard an already
                                        // downloaded estimate; the separate position-source switch controls its use.
                                        beaconDbEnabled = enabled
                                        beaconDbRequestToken += 1
                                        beaconDbLookupJob?.cancel()
                                        beaconDbLookupJob = null
                                        beaconDbLoading = false
                                        beaconDbLookupResult = null
                                        beaconDbMessage = if (enabled) {
                                            "Автообновление BeaconDB разрешено: при включённом фиксе обновляется оценка после изменения набора радиосигналов, не чаще одного запроса за 2 минуты; одинаковый набор освежается раз в 15 минут. Ручной поиск тоже доступен."
                                        } else {
                                            "Новые BeaconDB-запросы выключены. Ранее полученный кэш сохранён; его участие управляется отдельным переключателем источника позиции."
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
                                    onMode = { requestedMode -> selectPositionMode(requestedMode) },
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
                                    onBatteryProfile = { selected ->
                                        batteryProfile = selected
                                        prefs.edit().putString(ClearGpsPreferences.KEY_BATTERY_PROFILE, selected.key).apply()
                                        if (monitorEnabled) {
                                            val intent = Intent(context, LocationMonitorService::class.java)
                                                .setAction(LocationMonitorService.ACTION_START)
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                                            else context.startService(intent)
                                        }
                                    },
                                    onWifi = { useWifi = it; saveUseFlags() },
                                    onCells = { useCells = it; saveUseFlags() },
                                    onBle = { useBle = it; saveUseFlags() },
                                    onWifiSsidBlacklist = { value ->
                                        val bounded = value.take(RadioSsidFilter.MAX_CONFIG_LENGTH)
                                        wifiSsidBlacklist = bounded
                                        prefs.edit().putString(ClearGpsPreferences.KEY_WIFI_SSID_BLACKLIST, bounded).apply()
                                    },
                                    onShowPoints = { showRadioPointsOnMap = it },
                                    onManualFallback = {
                                        manualFallbackEnabled = it
                                        prefs.edit().putBoolean(ClearGpsPreferences.KEY_MANUAL_FALLBACK_ENABLED, it).apply()
                                    },
                                    onManualAnchor = { mapTapMode = MapTapMode.PLACE_ANCHOR },
                                    onClearData = { showDeleteAllDialog = true },
                                    onOpenLocationSettings = {
                                        try { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (_: Exception) { }
                                    },
                                    onRequestBatteryOptimizationExemption = { openBatteryOptimizationExemption(context) },
                                    onExportSettingsZoneBackup = {
                                        exportSettingsZoneBackupLauncher.launch("ClearGPS-settings-zones.cgsettings")
                                    },
                                    onImportSettingsZoneBackup = {
                                        importSettingsZoneBackupLauncher.launch(arrayOf("application/octet-stream", "text/plain", "*/*"))
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
                visible = selectedTab == AppTab.MAP && !panelVisible,
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

            if (selectedTab == AppTab.MAP) {
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
            if (selectedTab == AppTab.MAP) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = panelBottomOffset + 46.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surfaceContainerHigh.copy(alpha = 0.96f),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.clickable { showMapLegend = true }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Легенда", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp)
                    .padding(
                        bottom = panelBottomOffset +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            (if (panelVisible) 88.dp else 72.dp)
                    )
            )
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

    if (showSourceExportWarning) {
        AlertDialog(
            onDismissRequest = {
                showSourceExportWarning = false
                pendingSourceExportId = null
                pendingSourceExportDay = null
                pendingSourceExportType = null
                pendingSourceExportFileName = null
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Экспорт содержит личные радиоданные") },
            text = {
                Text(
                    "WiGLE CSV включает реальные идентификаторы радиосетей (в том числе BSSID/MAC), " +
                        "SSID, доступные координаты и время наблюдений. Перед сохранением проверь место назначения. " +
                        "Clear GPS не отправляет файл автоматически, но выбранное хранилище может синхронизировать его."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val fileName = pendingSourceExportFileName
                    showSourceExportWarning = false
                    if (pendingSourceExportId != null && fileName != null) {
                        exportSourceCsvLauncher.launch(fileName)
                    } else {
                        pendingSourceExportId = null
                        pendingSourceExportDay = null
                        pendingSourceExportType = null
                        pendingSourceExportFileName = null
                    }
                }) { Text("Продолжить") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSourceExportWarning = false
                    pendingSourceExportId = null
                    pendingSourceExportDay = null
                    pendingSourceExportType = null
                    pendingSourceExportFileName = null
                }) { Text("Отмена") }
            }
        )
    }

    if (showExportOptions) {
        AlertDialog(
            onDismissRequest = { showExportOptions = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Экспорт данных") },
            text = {
                Text(
                    "WiGLE CSV и WiGLE SQLite содержат реальные идентификаторы радио (включая BSSID/MAC), " +
                        "SSID, доступные координаты и время наблюдений. Clear GPS backup содержит полную локальную " +
                        "радиобазу и историю. Файл сохраняется в выбранном месте; Clear GPS не отправляет его автоматически, " +
                        "но стороннее хранилище может синхронизировать содержимое."
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
        if (showMapLegend && selectedTab == AppTab.MAP) {
            MapLegendBottomSheet(
                onDismiss = { showMapLegend = false },
                mode = positionMode,
                mapSourceMode = mapSourceMode,
                monitorEnabled = monitorEnabled,
                mockEnabled = mockEnabled,
                mockOutputActive = mockOutputActive,
                mapPointLimit = mapPointLimit,
                loadedMapPointCount = mapPoints.size,
                availableMapPointCount = geolocatedMapPointCount,
                mapPointsLoading = mapPointsLoading,
                onMapPointLimit = ::changeMapPointLimit
            )
        }
    }
}

private fun gpsFixStatusLabel(status: String): String = when {
    status.contains("Точное местоположение", ignoreCase = true) ||
        status.contains("precise location", ignoreCase = true) -> "Нужно точное разрешение"
    status.contains("GPS выключен", ignoreCase = true) ||
        status.contains("GPS provider disabled", ignoreCase = true) -> "GPS выключен"
    status.contains("GPS-провайдер недоступен", ignoreCase = true) ||
        status.contains("GPS provider unavailable", ignoreCase = true) -> "GPS недоступен"
    status.contains("не выдал координаты за 30 секунд", ignoreCase = true) -> "Нет GPS-фикса"
    status.contains("ожидание первого спутникового фикса", ignoreCase = true) -> "Ищем GPS-фикс"
    status.contains("Mock GPS paused", ignoreCase = true) -> "Позиция не допущена"
    status.contains("Mock GPS недоступен", ignoreCase = true) ||
        status.contains("Mock GPS unavailable", ignoreCase = true) -> "Mock GPS недоступен"
    else -> "Фикс ждёт координаты"
}

private fun gpsFixStatusHint(status: String): String? = when {
    status.contains("Точное местоположение", ignoreCase = true) ||
        status.contains("precise location", ignoreCase = true) ->
        "Для режима «Только GPS» выдай Clear GPS разрешение «Точное местоположение» в настройках Android."
    status.contains("GPS выключен", ignoreCase = true) ||
        status.contains("GPS provider disabled", ignoreCase = true) ->
        "GPS выключен в системных настройках геолокации. Включи геолокацию и повтори попытку."
    status.contains("GPS-провайдер недоступен", ignoreCase = true) ||
        status.contains("GPS provider unavailable", ignoreCase = true) ->
        "Android не предоставляет GPS-провайдер на этом устройстве."
    status.contains("не выдал координаты за 30 секунд", ignoreCase = true) ->
        "За 30 секунд GPS не получил координату. Проверь включение геолокации и попробуй под открытым небом; слабый спутниковый приём может увеличить ожидание."
    status.contains("ожидание первого спутникового фикса", ignoreCase = true) ->
        "GPS-провайдер запущен; ждём первый спутниковый фикс. При первом запуске холодный старт может занять больше времени."
    status.contains("Mock GPS paused", ignoreCase = true) ->
        "GPS-измерение получено, но сейчас фильтр не разрешил его публикацию. Открой диагностику GNSS, чтобы увидеть причину."
    status.contains("Mock GPS недоступен", ignoreCase = true) ||
        status.contains("Mock GPS unavailable", ignoreCase = true) ->
        "Координата подготовлена, но Android не разрешил mock-публикацию. Проверь выбор Clear GPS в настройках разработчика."
    status.isBlank() || status == "Monitoring" || status == "Мониторинг запущен" -> null
    else -> status.take(240)
}

@OptIn(ExperimentalMaterial3Api::class)
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
    riskEventSource: String,
    riskFeedbackCount: Int,
    onRiskFeedback: (RiskFeedbackLabel) -> Unit,
    onExportRiskFeedback: () -> Unit,
    onClearRiskFeedback: () -> Unit,
    replayRecording: Boolean,
    replayBytes: Long,
    replayStopReason: String,
    replaySaved: Boolean,
    replayCanStart: Boolean,
    replaySummary: String,
    onStartReplayRecord: () -> Unit,
    onStopReplayRecord: () -> Unit,
    onExportReplay: () -> Unit,
    onOpenReplay: () -> Unit,
    onDeleteReplay: () -> Unit,
    gnssEnvironment: String, gnssEnvironmentReasons: List<String>,
    gnssBaselineReady: Boolean, gnssBaselineDeviations: List<String>,
    gnssDiagnosticsAtNanos: Long, gnssDiagnosticsNowNanos: Long,
    gnssLiveSnapshot: GnssDiagnosticsLiveSnapshot?,
    onMode: (PositionMode) -> Unit, onFixToggle: () -> Unit, onScan: () -> Unit, onManualAnchor: () -> Unit,
    onRemoveManualAnchor: () -> Unit,
    obd2Connected: Boolean,
    onOpenObd2: () -> Unit,
    onRecording: (Boolean) -> Unit, onMonitoring: () -> Unit, onMock: () -> Unit, onOpenPoints: () -> Unit, onOpenZones: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var showDetails by remember { mutableStateOf(false) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var pendingUseGps by remember { mutableStateOf(true) }
    var pendingUseRadio by remember { mutableStateOf(true) }
    val sourceLabel = when (mode) {
        PositionMode.AUTO -> "GPS + радио"
        PositionMode.GPS_ONLY -> "Только GPS"
        PositionMode.RADIO_ONLY -> "Только радио"
    }

    val gpsWaitHint = if (mode.gpsOnly && monitorEnabled && !mockOutputActive) {
        gpsFixStatusHint(serviceStatus)
    } else null

    fun applyPendingSourceSelection() {
        val selectedMode = when {
            pendingUseGps && pendingUseRadio -> PositionMode.AUTO
            pendingUseGps -> PositionMode.GPS_ONLY
            pendingUseRadio -> PositionMode.RADIO_ONLY
            else -> mode
        }
        if (selectedMode != mode) onMode(selectedMode)
    }

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
                            fixEnabled -> gpsFixStatusLabel(serviceStatus)
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
                            PositionMode.GPS_ONLY -> PositionPublicationPolicy.gpsCoordinateHoldReason(
                                FilterState.values().firstOrNull { it.name == filterState } ?: FilterState.WAITING,
                                riskScore,
                                filterState == FilterState.ZONE_RISK.name
                            ) ?: "Ожидание GPS-фикса"
                            PositionMode.AUTO -> PositionPublicationPolicy.gpsCoordinateHoldReason(
                                FilterState.values().firstOrNull { it.name == filterState } ?: FilterState.WAITING,
                                riskScore,
                                filterState == FilterState.ZONE_RISK.name
                            ) ?: "Пока нет координат"
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
                                PositionPublicationPolicy.gpsCoordinateHoldReason(
                                    FilterState.values().firstOrNull { it.name == filterState } ?: FilterState.WAITING,
                                    riskScore,
                                    filterState == FilterState.ZONE_RISK.name
                                ) ?: gpsWaitHint ?: "Разреши точную геолокацию и включи фикс. Радиосканирование в режиме GPS отключено."
                            } else {
                                PositionPublicationPolicy.gpsCoordinateHoldReason(
                                    FilterState.values().firstOrNull { it.name == filterState } ?: FilterState.WAITING,
                                    riskScore,
                                    filterState == FilterState.ZONE_RISK.name
                                ) ?: "Разреши геолокацию и включи фикс. Разовый поиск доступен в диагностике."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
                val gpsPositionNotice = PositionPublicationPolicy.gpsCoordinateHoldReason(
                    FilterState.values().firstOrNull { it.name == filterState } ?: FilterState.WAITING,
                    riskScore,
                    filterState == FilterState.ZONE_RISK.name
                ) ?: gpsWaitHint
                if (coordinate != null && gpsPositionNotice != null) {
                    Text(
                        gpsPositionNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = colors.surfaceContainerLow,
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable {
                    pendingUseGps = mode != PositionMode.RADIO_ONLY
                    pendingUseRadio = mode != PositionMode.GPS_ONLY
                    sourceMenuExpanded = true
                }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.secondaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.GpsFixed, null, tint = colors.onSecondaryContainer)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Источники положения", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(sourceLabel, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ExpandMore, contentDescription = "Открыть выбор источников", tint = colors.onSurfaceVariant)
            }
        }
        if (sourceMenuExpanded) {
            ModalBottomSheet(
                onDismissRequest = {
                    applyPendingSourceSelection()
                    sourceMenuExpanded = false
                },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = colors.surfaceContainerHigh
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 620.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Источники положения", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(
                                "Изменения сохраняются автоматически при закрытии",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = {
                            applyPendingSourceSelection()
                            sourceMenuExpanded = false
                        }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Закрыть и применить выбор")
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            pendingUseGps = true
                            pendingUseRadio = true
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Outlined.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Включить GPS и радио")
                    }

                    Text("ИСТОЧНИКИ КООРДИНАТ", style = MaterialTheme.typography.labelLarge, color = colors.primary)

                    PositionSourceOption(
                        title = "GPS / GNSS",
                        description = "Координата системного провайдера Android.",
                        icon = Icons.Outlined.GpsFixed,
                        checked = pendingUseGps,
                        enabled = !pendingUseGps || pendingUseRadio,
                        onCheckedChange = { checked ->
                            if (checked || pendingUseRadio) pendingUseGps = checked
                        }
                    )
                    PositionSourceOption(
                        title = "Локальная радиобаза",
                        description = "Оценка положения по совпадениям Wi-Fi и Bluetooth/сотовых точек.",
                        icon = Icons.Outlined.Storage,
                        checked = pendingUseRadio,
                        enabled = !pendingUseRadio || pendingUseGps,
                        onCheckedChange = { checked ->
                            if (checked || pendingUseGps) pendingUseRadio = checked
                        }
                    )

                    Divider(color = colors.outlineVariant)

                    Text("ВСПОМОГАТЕЛЬНЫЕ СИГНАЛЫ", style = MaterialTheme.typography.labelLarge, color = colors.primary)

                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            applyPendingSourceSelection()
                            sourceMenuExpanded = false
                            onOpenObd2()
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = colors.surfaceContainer
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = colors.tertiaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.DirectionsCar, null, tint = colors.onTertiaryContainer)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("OBD-II", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (obd2Connected) "Подключён · вспомогательный сигнал"
                                    else "Не подключён · открыть подключение",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Outlined.ExpandMore, contentDescription = "Открыть OBD-II", tint = colors.onSurfaceVariant)
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = colors.surfaceContainer
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = colors.surfaceContainerHigh
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.Info, null, tint = colors.onSurfaceVariant)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("IMU и барометр", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Датчики доверия и движения, не самостоятельные источники координат.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
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
                RiskFeedbackPanel(
                    filterState = filterState,
                    riskScore = riskScore,
                    riskSource = riskEventSource,
                    riskReasons = riskReasons,
                    riskBreakdown = riskBreakdown,
                    monitorEnabled = monitorEnabled,
                    radioOnly = mode.usesRadio,
                    savedCount = riskFeedbackCount,
                    onLabel = onRiskFeedback,
                    onExport = onExportRiskFeedback,
                    onClear = onClearRiskFeedback
                )
                GnssReplayPanel(
                    monitorEnabled=monitorEnabled, radioOnly=mode.usesRadio,
                    radioCaptureAvailable=mode == PositionMode.AUTO,
                    recording=replayRecording, canStart=replayCanStart,
                    hasSavedLog=replaySaved, sizeBytes=replayBytes,
                    stopReason=replayStopReason, replayResult=replaySummary,
                    onStart=onStartReplayRecord, onStop=onStopReplayRecord,
                    onExport=onExportReplay, onImport=onOpenReplay, onDelete=onDeleteReplay
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
                    "Радиоидентификаторы остаются на устройстве. Только вручную разрешённый BeaconDB-запрос отправляет текущие допустимые Wi-Fi/BLE/сотовые идентификаторы и RSSI; OpenCellID отправляет только текущие MCC/MNC/LAC или TAC/Cell ID и личный API-ключ. Обычные сканы используют локальные данные и кэш, без сетевых запросов к этим сервисам. API WiGLE не вызывается. Онлайн-карта OpenFreeMap видит сетевые метаданные запросов тайлов.",
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
    mapPointLoadedCount: Int, geolocatedPointCount: Int,
    observationRetentionLimit: Int, retentionLimitUpdating: Boolean, onObservationRetentionLimit: (Int) -> Unit,
    searchQuery: String, onSearch: (String) -> Unit,
    sources: List<RadioSourceRecord>,
    sourceDaysBySource: Map<Long, List<RadioSourceDayRecord>>,
    sourceSegmentsBySource: Map<Long, List<RadioSourceSegmentRecord>>,
    onSetSourceEnabled: (RadioSourceRecord, Boolean) -> Unit,
    onSetSourceTrustWeight: (RadioSourceRecord, Double) -> Unit,
    onDeleteSource: (RadioSourceRecord) -> Unit,
    onExportSource: (RadioSourceRecord) -> Unit,
    onExportSourceDay: (RadioSourceRecord, RadioSourceDayRecord) -> Unit,
    onDeleteSourceDay: (RadioSourceDayRecord) -> Unit,
    onSetSourceSegmentEnabled: (RadioSourceSegmentRecord, Boolean) -> Unit,
    onSetSourceSegmentTrustWeight: (RadioSourceSegmentRecord, Double) -> Unit,
    onDeleteSourceSegment: (RadioSourceSegmentRecord) -> Unit,
    onExportSourceSegment: (RadioSourceSegmentRecord) -> Unit,
    typeFilter: RadioPointTypeFilter, onTypeFilter: (RadioPointTypeFilter) -> Unit,
    selectedView: PointsView, onView: (PointsView) -> Unit, onEdit: (RadioPointRecord) -> Unit,
    onDelete: (RadioPointRecord) -> Unit, onImport: () -> Unit, onExport: () -> Unit, onClear: () -> Unit, onRefresh: () -> Unit,
    dataSource: DatabaseDataSource,
    localPositionSourceEnabled: Boolean, beaconDbPositionSourceEnabled: Boolean,
    openCellIdPositionSourceEnabled: Boolean,
    onLocalPositionSourceEnabled: (Boolean) -> Unit,
    onBeaconDbPositionSourceEnabled: (Boolean) -> Unit,
    onOpenCellIdPositionSourceEnabled: (Boolean) -> Unit,
    beaconDbEnabled: Boolean, beaconDbLoading: Boolean,
    beaconDbResult: BeaconDbLookupResult?, beaconDbMessage: String,
    onDataSource: (DatabaseDataSource) -> Unit, onBeaconDbLookup: () -> Unit,
    onPublishBeaconDbLocation: () -> Unit, onOpenBeaconDbSettings: () -> Unit,
    openCellIdRecords: List<OpenCellIdCellRecord>, openCellIdKeyConfigured: Boolean,
    openCellIdKeyInput: String, openCellIdEnabled: Boolean, openCellIdLoading: Boolean,
    openCellIdMessage: String, onOpenCellIdKeyInput: (String) -> Unit,
    onSaveOpenCellIdKey: () -> Unit, onForgetOpenCellIdKey: () -> Unit,
    onOpenCellIdEnabled: (Boolean) -> Unit, onOpenCellIdLookup: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val databaseScrollState = rememberScrollState()
    var pendingDeleteSource by remember { mutableStateOf<RadioSourceRecord?>(null) }
    var pendingDeleteSourceDay by remember { mutableStateOf<RadioSourceDayRecord?>(null) }
    var expandedSourceIds by remember { mutableStateOf(emptySet<Long>()) }
    var expandedSourceDayKeys by remember { mutableStateOf(emptySet<String>()) }
    var pendingDeleteSourceSegment by remember { mutableStateOf<RadioSourceSegmentRecord?>(null) }
    var sourceNameFilter by remember { mutableStateOf("") }
    var sourceDateFilter by remember { mutableStateOf("") }
    var sourceTypeFilter by remember { mutableStateOf("all") }
    var sourceMinRssi by remember { mutableStateOf(-110f) }
    var sourceMinObservations by remember { mutableStateOf(1f) }
    val filteredPoints = points.filter { point -> typeFilter.type == null || point.type == typeFilter.type }
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(databaseScrollState)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
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
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = colors.surfaceContainer
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("Источники объединённой позиции", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Эти переключатели определяют, какие независимые базы участвуют в расчёте координаты. Они не удаляют данные и не включают автоматические сетевые запросы.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                )
                FusionSourceToggleRow(
                    title = "Локальная база",
                    description = "Исторические радиоснимки, импорт WiGLE и вручную привязанные точки.",
                    checked = localPositionSourceEnabled,
                    onCheckedChange = onLocalPositionSourceEnabled
                )
                Divider(color = colors.outlineVariant)
                FusionSourceToggleRow(
                    title = "BeaconDB",
                    description = "Используется только уже полученная кешированная оценка. Новые запросы — отдельно вручную и только при разрешении BeaconDB в настройках.",
                    checked = beaconDbPositionSourceEnabled,
                    onCheckedChange = onBeaconDbPositionSourceEnabled
                )
                Divider(color = colors.outlineVariant)
                FusionSourceToggleRow(
                    title = "OpenCellID",
                    description = "Используются координаты текущих вышек из локального кэша; обычное сканирование API не вызывает.",
                    checked = openCellIdPositionSourceEnabled,
                    onCheckedChange = onOpenCellIdPositionSourceEnabled
                )
            }
        }
        if (dataSource == DatabaseDataSource.LOCAL) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Локальная база", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(pointCount.toString() + " точек · " + observationCount + " наблюдений",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(
                    "Загружено в список: " + points.size + " из " + pointCount +
                        "; отображается после фильтра: " + filteredPoints.size +
                        ". На карте загружено " + mapPointLoadedCount + " из " + geolocatedPointCount +
                        " точек с координатами. Точки без координат остаются в базе, но не отображаются маркерами.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
            IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Обновить список") }
        }
        Surface(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = colors.surfaceContainer
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Лимит подробных наблюдений", fontWeight = FontWeight.SemiBold)
                Text(
                    "$observationCount / $observationRetentionLimit записей. Старейшие наблюдения удаляются при превышении; сводные точки и координаты сохраняются. История сканирований ограничена 500 записями.",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    RadioDatabaseRetentionPolicy.LIMIT_OPTIONS.forEach { limit ->
                        val selected = observationRetentionLimit == limit
                        AssistChip(
                            onClick = { onObservationRetentionLimit(limit) },
                            enabled = !retentionLimitUpdating,
                            label = { Text(RadioDatabaseRetentionPolicy.label(limit)) },
                            colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                containerColor = if (selected) colors.secondaryContainer else colors.surfaceVariant,
                                labelColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { onView(PointsView.POINTS) }, label = { Text("Точки") }, leadingIcon = { Icon(Icons.Outlined.Storage, null) })
            AssistChip(onClick = { onView(PointsView.HISTORY) }, label = { Text("История") }, leadingIcon = { Icon(Icons.Outlined.History, null) })
            AssistChip(onClick = { onView(PointsView.SOURCES) }, label = { Text("Источники") }, leadingIcon = { Icon(Icons.Outlined.Storage, null) })
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
        Text(when (selectedView) {
            PointsView.POINTS -> "SSID и BSSID хранятся только локально. Нажми на точку на карте для просмотра подробностей или редактирования."
            PointsView.HISTORY -> "Хранятся последние 500 сканирований. История остаётся на устройстве."
            PointsView.SOURCES -> "Импортированные источники можно отключать, изменять доверие, экспортировать или удалять. Всё хранится локально."
        }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)

        if (selectedView == PointsView.POINTS) {
            if (filteredPoints.isEmpty()) {
                if (points.isEmpty()) EmptyState("Точек пока нет", "Включи запись на карте или импортируй WiGLE CSV.")
                else EmptyState("Нет точек этого типа", "Выбери «Все» или другой тип: Wi‑Fi, вышки либо Bluetooth.")
            } else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                items(filteredPoints, key = { it.id }) { point ->
                    RadioPointRow(point, onEdit = { onEdit(point) }, onDelete = { onDelete(point) })
                }
            }
        } else if (selectedView == PointsView.HISTORY) {
            if (history.isEmpty()) EmptyState("История пуста", "Первый запуск сканирования добавит сюда запись.")
            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
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
        } else {
            val sourceFilterPolicy = RadioSourceFilterPolicy(
                sourceNameQuery = sourceNameFilter,
                dateQuery = sourceDateFilter,
                radioType = sourceTypeFilter,
                minimumRssi = sourceMinRssi.toInt(),
                minimumObservations = sourceMinObservations.toInt()
            )
            val segmentsBySource = sourceSegmentsBySource.mapValues { (_, rows) ->
                rows.filter(sourceFilterPolicy::matchesSegment)
            }
            val daysBySource = sourceDaysBySource.mapValues { (sourceId, days) ->
                val matchingSegments = segmentsBySource[sourceId].orEmpty()
                days.filter { day -> sourceFilterPolicy.matchesDay(day.dayKey, matchingSegments) }
            }
            val visibleSources = sources.filter { source ->
                sourceFilterPolicy.matchesSource(source, daysBySource[source.id].orEmpty().isNotEmpty())
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sourceNameFilter,
                        onValueChange = { sourceNameFilter = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Источник") },
                        placeholder = { Text("WiGLE, Clear GPS…") }
                    )
                    OutlinedTextField(
                        value = sourceDateFilter,
                        onValueChange = { sourceDateFilter = it.take(10) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Дата") },
                        placeholder = { Text("YYYY-MM-DD") }
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AssistChip(onClick = { sourceTypeFilter = "all" }, label = { Text(if (sourceTypeFilter == "all") "✓ Все" else "Все") })
                    AssistChip(onClick = { sourceTypeFilter = "wifi" }, label = { Text(if (sourceTypeFilter == "wifi") "✓ Wi‑Fi" else "Wi‑Fi") })
                    AssistChip(onClick = { sourceTypeFilter = "cell" }, label = { Text(if (sourceTypeFilter == "cell") "✓ Соты" else "Соты") })
                    AssistChip(onClick = { sourceTypeFilter = "ble" }, label = { Text(if (sourceTypeFilter == "ble") "✓ BLE" else "BLE") })
                }
                Text("Минимальный RSSI: " + sourceMinRssi.toInt() + " dBm", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = sourceMinRssi,
                    onValueChange = { sourceMinRssi = it },
                    valueRange = -110f..-20f,
                    steps = 17
                )
                Text("Минимум наблюдений: " + sourceMinObservations.toInt(), style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = sourceMinObservations,
                    onValueChange = { sourceMinObservations = it },
                    valueRange = 1f..100f,
                    steps = 98
                )
                if (visibleSources.isEmpty()) {
                    EmptyState("Нет совпадений", "Измени название источника, дату, тип радио, RSSI или число наблюдений.")
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visibleSources, key = { it.id }) { source ->
                            RadioSourceRow(
                                source = source,
                                days = daysBySource[source.id].orEmpty(),
                                segments = segmentsBySource[source.id].orEmpty(),
                                daysExpanded = source.id in expandedSourceIds,
                                expandedDayKeys = expandedSourceDayKeys,
                                onToggleDays = {
                                    expandedSourceIds = if (source.id in expandedSourceIds) expandedSourceIds - source.id else expandedSourceIds + source.id
                                },
                                onToggleDaySegments = { day ->
                                    val key = source.id.toString() + ":" + day.dayKey
                                    expandedSourceDayKeys = if (key in expandedSourceDayKeys) expandedSourceDayKeys - key else expandedSourceDayKeys + key
                                },
                                onEnabled = { enabled -> onSetSourceEnabled(source, enabled) },
                                onTrustWeight = { weight -> onSetSourceTrustWeight(source, weight) },
                                onExport = { onExportSource(source) },
                                onDelete = { pendingDeleteSource = source },
                                onExportDay = { day -> onExportSourceDay(source, day) },
                                onDeleteDay = { day -> pendingDeleteSourceDay = day },
                                onSetSegmentEnabled = onSetSourceSegmentEnabled,
                                onSetSegmentTrustWeight = onSetSourceSegmentTrustWeight,
                                onExportSegment = onExportSourceSegment,
                                onDeleteSegment = { segment -> pendingDeleteSourceSegment = segment }
                            )
                        }
                    }
                }
            }
        }
        val sourcePendingDeletion = pendingDeleteSource
        if (sourcePendingDeletion != null) {
            AlertDialog(
                onDismissRequest = { pendingDeleteSource = null },
                title = { Text("Удалить источник?") },
                text = {
                    Text(
                        "«" + sourcePendingDeletion.name + "»: будут удалены его подробные наблюдения. Координаты радиоточек будут пересчитаны по остальным включённым источникам; если данных не останется, точка станет без координат или исчезнет."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDeleteSource = null
                        onDeleteSource(sourcePendingDeletion)
                    }) { Text("Удалить") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteSource = null }) { Text("Отмена") }
                }
            )
        }
        val segmentPendingDeletion = pendingDeleteSourceSegment
        if (segmentPendingDeletion != null) {
            AlertDialog(
                onDismissRequest = { pendingDeleteSourceSegment = null },
                title = { Text("Удалить запись " + segmentPendingDeletion.radioType + "?") },
                text = {
                    Text("Будут удалены только наблюдения источника за " + segmentPendingDeletion.dayKey +
                        " для типа " + segmentPendingDeletion.radioType +
                        ". Остальные типы, даты и источники сохраняются; затронутые координаты пересчитываются.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDeleteSourceSegment = null
                        onDeleteSourceSegment(segmentPendingDeletion)
                    }) { Text("Удалить запись") }
                },
                dismissButton = { TextButton(onClick = { pendingDeleteSourceSegment = null }) { Text("Отмена") } }
            )
        }
        val dayPendingDeletion = pendingDeleteSourceDay
        if (dayPendingDeletion != null) {
            AlertDialog(
                onDismissRequest = { pendingDeleteSourceDay = null },
                title = { Text("Удалить день " + dayPendingDeletion.dayKey + "?") },
                text = {
                    Text("Будут удалены подробные наблюдения этого источника за выбранный локальный календарный день. Другие дни не затрагиваются; затронутые координаты пересчитываются по оставшимся данным. Для локальной записи также удаляется история сканирований за этот день.")
                },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDeleteSourceDay = null
                        onDeleteSourceDay(dayPendingDeletion)
                    }) { Text("Удалить день") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeleteSourceDay = null }) { Text("Отмена") }
                }
            )
        }
        } else if (dataSource == DatabaseDataSource.BEACONDB) {
            BeaconDbDatabaseContent(
                modifier = Modifier.fillMaxWidth(),
                enabled = beaconDbEnabled,
                loading = beaconDbLoading,
                result = beaconDbResult,
                message = beaconDbMessage,
                onLookup = onBeaconDbLookup,
                onPublishAsMock = onPublishBeaconDbLocation,
                onOpenSettings = onOpenBeaconDbSettings
            )
        } else {
            OpenCellIdDatabaseContent(
                modifier = Modifier.fillMaxWidth(),
                records = openCellIdRecords,
                keyConfigured = openCellIdKeyConfigured,
                keyInput = openCellIdKeyInput,
                enabled = openCellIdEnabled,
                loading = openCellIdLoading,
                message = openCellIdMessage,
                onKeyInput = onOpenCellIdKeyInput,
                onSaveKey = onSaveOpenCellIdKey,
                onForgetKey = onForgetOpenCellIdKey,
                onEnabled = onOpenCellIdEnabled,
                onLookup = onOpenCellIdLookup
            )
        }
    }
}


@Composable
private fun RadioSourceRow(
    source: RadioSourceRecord,
    days: List<RadioSourceDayRecord>,
    segments: List<RadioSourceSegmentRecord>,
    daysExpanded: Boolean,
    expandedDayKeys: Set<String>,
    onToggleDays: () -> Unit,
    onToggleDaySegments: (RadioSourceDayRecord) -> Unit,
    onEnabled: (Boolean) -> Unit,
    onTrustWeight: (Double) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onExportDay: (RadioSourceDayRecord) -> Unit,
    onDeleteDay: (RadioSourceDayRecord) -> Unit,
    onSetSegmentEnabled: (RadioSourceSegmentRecord, Boolean) -> Unit,
    onSetSegmentTrustWeight: (RadioSourceSegmentRecord, Double) -> Unit,
    onExportSegment: (RadioSourceSegmentRecord) -> Unit,
    onDeleteSegment: (RadioSourceSegmentRecord) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var trustWeight by remember(source.id, source.trustWeight) {
        mutableStateOf(source.trustWeight.toFloat().coerceIn(0f, 1f))
    }
    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainerHigh
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(source.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Тип: " + when (source.kind) {
                            "local" -> "локальная запись"
                            "wigle" -> "WiGLE"
                            "custom" -> "импорт Clear GPS / SQLite"
                            "beacondb" -> "BeaconDB"
                            else -> source.kind
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        source.radioCount.toString() + " радиоточек · " + source.observationCount + " наблюдений",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Text(
                        "Добавлен: " + dateTime(source.createdAt) + " · последний раз: " + dateTime(source.lastSeen),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
                Checkbox(checked = source.enabled, onCheckedChange = onEnabled, enabled = source.kind != "local")
            }
            Text(
                if (source.enabled) "Участвует в радиопозиционировании" else "Отключён от радиопозиционирования",
                style = MaterialTheme.typography.bodySmall,
                color = if (source.enabled) colors.primary else colors.onSurfaceVariant
            )
            if (source.kind == "local") {
                Text(
                    "Локальные наблюдения нельзя отключить или удалить. Вес доверия всегда 100%.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            } else {
                Text(
                    "Вес доверия: " + String.format(Locale.ROOT, "%.0f%%", trustWeight * 100f) + ". Ноль полностью исключает источник из оценок.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
                Slider(
                    value = trustWeight,
                    onValueChange = { trustWeight = it.coerceIn(0f, 1f) },
                    onValueChangeFinished = { onTrustWeight(trustWeight.toDouble()) },
                    valueRange = 0f..1f,
                    steps = 9
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onExport,
                        enabled = source.radioCount > 0,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Экспорт CSV") }
                    TextButton(onClick = onDelete) { Text("Удалить источник") }
                }
            }
            Divider(color = colors.outlineVariant)
            TextButton(onClick = onToggleDays) {
                Text((if (daysExpanded) "Скрыть дни" else "Показать дни") + " (" + days.size + ")")
            }
            if (daysExpanded) {
                if (days.isEmpty()) {
                    Text("Нет подробных наблюдений по дням. Сводные радиоточки сохранены отдельно.",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(days, key = { it.dayKey }) { day ->
                            val expandedKey = source.id.toString() + ":" + day.dayKey
                            val daySegments = segments.filter { it.dayKey == day.dayKey }
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = colors.surface
                            ) {
                                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(day.dayKey, fontWeight = FontWeight.SemiBold)
                                            Text(day.observationCount.toString() + " наблюдений · " + day.radioCount + " радиоточек",
                                                style = MaterialTheme.typography.bodySmall)
                                            Text("Wi‑Fi " + day.wifiObservations + " · соты " + day.cellObservations + " · BLE " + day.bleObservations,
                                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            TextButton(onClick = { onExportDay(day) }) { Text("CSV") }
                                            TextButton(onClick = { onDeleteDay(day) }) { Text("Удалить день") }
                                        }
                                    }
                                    TextButton(onClick = { onToggleDaySegments(day) }) {
                                        Text((if (expandedKey in expandedDayKeys) "Скрыть записи" else "Записи по типу") + " (" + daySegments.size + ")")
                                    }
                                    if (expandedKey in expandedDayKeys) {
                                        if (daySegments.isEmpty()) {
                                            Text("Нет записей для выбранных фильтров.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        } else {
                                            daySegments.forEach { segment ->
                                                RadioSourceSegmentRow(
                                                    segment = segment,
                                                    onEnabled = { enabled -> onSetSegmentEnabled(segment, enabled) },
                                                    onTrustWeight = { weight -> onSetSegmentTrustWeight(segment, weight) },
                                                    onExport = { onExportSegment(segment) },
                                                    onDelete = { onDeleteSegment(segment) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun RadioSourceSegmentRow(
    segment: RadioSourceSegmentRecord,
    onEnabled: (Boolean) -> Unit,
    onTrustWeight: (Double) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var weight by remember(segment.sourceId, segment.dayKey, segment.radioType, segment.trustWeight) {
        mutableStateOf(segment.trustWeight.toFloat().coerceIn(0f, 1f))
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceContainerHigh
    ) {
        Column(Modifier.padding(9.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        when (segment.radioType) {
                            "wifi" -> "Wi‑Fi"
                            "cell" -> "Сотовая сеть"
                            "ble" -> "Bluetooth LE"
                            else -> segment.radioType
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(segment.radioCount.toString() + " точек · " + segment.observationCount + " наблюдений",
                        style = MaterialTheme.typography.bodySmall)
                    Text("RSSI " + segment.minRssi + "… " + segment.maxRssi + " dBm",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                Checkbox(checked = segment.enabled, onCheckedChange = onEnabled)
            }
            Text(
                "Доверие: " + String.format(Locale.ROOT, "%.0f%%", weight * 100f) +
                    (if (segment.enabled) " · включено" else " · выключено"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Slider(
                value = weight,
                onValueChange = { weight = it.coerceIn(0f, 1f) },
                onValueChangeFinished = { onTrustWeight(weight.toDouble()) },
                valueRange = 0f..1f,
                steps = 9
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) { Text("CSV") }
                TextButton(onClick = onDelete) { Text("Удалить запись") }
            }
        }
    }
}

@Composable
private fun FusionSourceToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun OpenCellIdDatabaseContent(
    modifier: Modifier = Modifier,
    records: List<OpenCellIdCellRecord>,
    keyConfigured: Boolean,
    keyInput: String,
    enabled: Boolean,
    loading: Boolean,
    message: String,
    onKeyInput: (String) -> Unit,
    onSaveKey: () -> Unit,
    onForgetKey: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onLookup: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val usage = OpenCellIdUsageBudget.snapshot(context)
    Column(modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("OpenCellID · примерные координаты сотовых ячеек",
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Здесь показан отдельный каталог примерных координат сотовых ячеек, а не список BLE/Wi-Fi-точек карты. Read-only поиск выполняется по текущим GSM/UMTS/LTE/NR ячейкам Android. OpenCellID возвращает расчётное положение ячейки по наблюдениям сообщества; оно может отличаться от физического места антенны и не является координатой телефона. Wi-Fi/BSSID, BLE, GPS-координаты и история сюда не отправляются.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                )
            }
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Разрешить ручные запросы", fontWeight = FontWeight.SemiBold)
                        Text("Сетевой вызов выполняется только после нажатия кнопки ниже.",
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    Switch(checked = enabled, onCheckedChange = onEnabled, enabled = !loading)
                }
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = onKeyInput,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Личный API-ключ OpenCellID") },
                    placeholder = { Text(if (keyConfigured) "Ключ сохранён; введи новый для замены" else "Вставь ключ из своего аккаунта") },
                    visualTransformation = PasswordVisualTransformation()
                )
                Text(
                    if (keyConfigured) "Ключ хранится в зашифрованном виде через Android Keystore."
                    else "Ключ ещё не сохранён. Получи его в аккаунте OpenCellID.",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = onSaveKey,
                        enabled = keyInput.isNotBlank() && !loading,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)
                    ) { Text(if (keyConfigured) "Заменить ключ" else "Сохранить ключ") }
                    TextButton(onClick = onForgetKey, enabled = keyConfigured && !loading) {
                        Text("Удалить ключ")
                    }
                }
            }
        }
        FilledTonalButton(
            onClick = onLookup,
            enabled = enabled && keyConfigured && !loading,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Outlined.Refresh, null)
            Spacer(Modifier.width(7.dp))
            Text(if (loading) "Запрашиваю OpenCellID…" else "Найти координаты текущих сот")
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(message, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = colors.surfaceContainer) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Экономия запросов: ${usage.requestsUsed}/${OpenCellIdUsageBudget.DAILY_APP_BUDGET} попыток Clear GPS за UTC-день; осталось ${usage.requestsRemaining}.",
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Не более ${OpenCellIdLookupPolicy.MAX_LOOKUPS_PER_ACTION} новых ячеек за пакет; пауза между пакетами — 15 минут. Найденные ячейки кэшируются 30 дней, отсутствующие — 7 дней.",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
                )
                if (usage.serverLimitReached) {
                    Text(
                        "OpenCellID сообщил об исчерпании серверной квоты. Clear GPS выдержит паузу до следующей UTC-даты; фактический сброс задаёт сервис.",
                        style = MaterialTheme.typography.labelSmall, color = colors.error
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Найдено в кэше: ${records.size}", fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://opencellid.org")))
            }) { Text("OpenCellID") }
        }
        if (records.isEmpty()) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.surfaceContainer) {
                Text("Пока нет найденных вышек. Ответы «не найдено» тоже кэшируются, чтобы не расходовать квоту повторно.",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(records, key = { it.key.toString() }) { cell ->
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.surfaceContainerHigh) {
                        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${cell.key.radio} · MCC ${cell.key.mcc} / MNC ${cell.key.mnc}",
                                fontWeight = FontWeight.SemiBold)
                            Text("LAC/TAC ${cell.key.area} · Cell ID ${cell.key.cellId}",
                                style = MaterialTheme.typography.bodySmall)
                            Text(String.format(Locale.US, "%.4f, %.4f", cell.latitude, cell.longitude),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Радиус: " + (cell.rangeMeters?.let { "≈${it} м" } ?: "не указан") +
                                    " · измерений в базе: ${cell.samples}",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                            )
                            Text("Проверено: " + dateTime(cell.checkedAtMillis),
                                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Text(
            "Attribution: расчётные координаты сотовых ячеек OpenCellID, CC BY-SA 4.0. " +
                "Радиус — оценка базы, а не гарантированная зона покрытия. Официальная квота сервиса — до 1 000 кредитов в сутки на пользователя; " +
                "Clear GPS ограничивает себя 100 попытками в UTC-день. Данные не добавляются в локальные fingerprints и не публикуются в Mock GPS.",
            style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://creativecommons.org/licenses/by-sa/4.0/")))
            }) { Text("Лицензия CC BY-SA 4.0") }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.opencellid.org/docs/attribution")))
            }) { Text("Условия атрибуции") }
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
                Text("BeaconDB · оценка положения", fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium)
                Text(
                    "Здесь показывается одна оценка координат для текущего радиоснимка, а не список отдельных Wi-Fi/BLE-точек или копия базы BeaconDB. Ручной запрос оценивает положение по видимым Wi-Fi BSSID, стабильным BLE-маякам и поддерживаемым сотовым ячейкам. SSID, имена Bluetooth и рекламные payload не отправляются; локальная SQLite-база не меняется.",
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
                        "Пока выключено, Clear GPS ничего не отправляет в BeaconDB. Чтобы выполнить поиск, явно разреши запросы по Wi-Fi/BLE/сотам в настройках.",
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
                Text(if (loading) "Запрашиваю BeaconDB…" else "Оценить положение по Wi-Fi, Bluetooth и сотам")
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
                            if (result.fromCache) {
                                val cacheAge = if (result.cacheAgeMillis < 1_000L) {
                                    "менее 1 с"
                                } else {
                                    (result.cacheAgeMillis / 1_000L).toString() + " с"
                                }
                                Text(
                                    "Временный кэш · возраст при повторном поиске: " + cacheAge + ".",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
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
                            "В запросе: Wi-Fi " + result.wifiAccessPoints.size +
                                " · BLE " + result.bluetoothBeacons.size +
                                " · соты " + result.cells.size +
                                if (result.unsupportedCellCount > 0) " · неподдерживаемых ячеек пропущено: " +
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
                if (result.cells.isNotEmpty() || result.wifiAccessPoints.isNotEmpty() ||
                    result.bluetoothBeacons.isNotEmpty()
                ) {
                    Text("Радиосигналы, включённые в запрос",
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("Это временный снимок Android для запроса, а не выгрузка каталога BeaconDB. SSID и имена Bluetooth не отправляются.",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (result.wifiAccessPoints.isNotEmpty()) {
                            item { Text("Wi-Fi · ${result.wifiAccessPoints.size}", style = MaterialTheme.typography.labelLarge, color = colors.primary) }
                            items(result.wifiAccessPoints, key = { "wifi:" + it.identifier }) { point ->
                                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), color = colors.surfaceContainerHigh) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Wifi, null, tint = colors.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(point.macAddress, fontWeight = FontWeight.SemiBold)
                                            Text((point.frequencyMhz?.let { "${it} MHz · " } ?: "") +
                                                (point.ageMillis?.let { "возраст ${it / 1_000} с" } ?: "возраст неизвестен"),
                                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        }
                                        Text(point.signalStrength.toString() + " dBm",
                                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if (result.bluetoothBeacons.isNotEmpty()) {
                            item { Text("Стабильные BLE-маяки · ${result.bluetoothBeacons.size}", style = MaterialTheme.typography.labelLarge, color = colors.primary) }
                            items(result.bluetoothBeacons, key = { "ble:" + it.identifier }) { beacon ->
                                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), color = colors.surfaceContainerHigh) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Bluetooth, null, tint = colors.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(beacon.macAddress, fontWeight = FontWeight.SemiBold)
                                            Text(beacon.ageMillis?.let { "возраст ${it / 1_000} с" } ?: "возраст неизвестен",
                                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        }
                                        Text(beacon.signalStrength.toString() + " dBm",
                                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if (result.cells.isNotEmpty()) {
                            item { Text("Сотовые ячейки · ${result.cells.size}", style = MaterialTheme.typography.labelLarge, color = colors.primary) }
                            items(result.cells, key = { "cell:" + it.identifier }) { cell ->
                                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp), color = colors.surfaceContainerHigh) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.SignalCellularAlt, null, tint = colors.primary)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(cell.radioType.uppercase() + " · " + cell.mobileCountryCode + "/" + cell.mobileNetworkCode,
                                                fontWeight = FontWeight.SemiBold)
                                            Text("Зона " + cell.locationAreaCode + " · ID соты " + cell.cellId,
                                                style = MaterialTheme.typography.bodySmall)
                                        }
                                        Text(cell.signalStrength.toString() + " dBm",
                                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                    }
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
            Text("Импортированная база: " + (observation.sourceName?.takeIf { it.isNotBlank() } ?: "не указана"),
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ZonesScreen(
    zones: List<SavedRiskZone>, draftZone: List<ZonePoint>, mapTapMode: MapTapMode,
    airportAvoidanceEnabled: Boolean, airportRadiusMeters: Int, airportExclusions: String,
    airportCatalogOnlineEnabled: Boolean, airportCatalogAvailable: Boolean, airportCatalogStale: Boolean,
    airportCatalogCount: Int, airportCatalogUpdatedAt: Long, airportCatalogSource: String,
    airportCatalogRefreshing: Boolean,
    onAirportAvoidanceEnabled: (Boolean) -> Unit, onAirportRadiusMeters: (Int) -> Unit,
    onAirportExclusions: (String) -> Unit, onAirportCatalogOnlineEnabled: (Boolean) -> Unit,
    onRefreshAirportCatalog: () -> Unit,
    onStartDraw: () -> Unit, onSaveDraft: () -> Unit,
    onToggle: (SavedRiskZone, Boolean) -> Unit, onRename: (SavedRiskZone) -> Unit,
    onDelete: (SavedRiskZone) -> Unit, onCancelDraw: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    // A stale/missing offline catalogue may use the read-only online fallback only after consent.
    LaunchedEffect(airportCatalogOnlineEnabled, airportCatalogAvailable, airportCatalogStale) {
        if (airportCatalogOnlineEnabled && (!airportCatalogAvailable || airportCatalogStale)) {
            onRefreshAirportCatalog()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Зоны спуфинга", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Отмечай участки, где телефон показывает ложное или скачкообразное местоположение. Каждая зона хранится локально и может отдельно включаться в фильтре.",
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = colors.surfaceContainer) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Аэропорты", fontWeight = FontWeight.SemiBold)
                        Text(
                            "В радиусе аэропорта радио-точки не записываются и не участвуют в оценке. Близость к аэропорту добавляет только слабый контекстный сигнал риска — это не жёсткое состояние ZONE_RISK.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = airportAvoidanceEnabled,
                        onCheckedChange = onAirportAvoidanceEnabled
                    )
                }
                Text("Радиус исключения: ${airportRadiusMeters} м", fontWeight = FontWeight.Medium)
                Slider(
                    value = airportRadiusMeters.toFloat(),
                    onValueChange = { onAirportRadiusMeters(it.toInt()) },
                    valueRange = SpoofZoneService.MIN_AIRPORT_RADIUS_METERS.toFloat()..
                        SpoofZoneService.MAX_AIRPORT_RADIUS_METERS.toFloat(),
                    steps = 19,
                    enabled = airportAvoidanceEnabled
                )
                OutlinedTextField(
                    value = airportExclusions,
                    onValueChange = onAirportExclusions,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Исключения ICAO/IATA") },
                    placeholder = { Text("LSGG, GVA") },
                    supportingText = { Text("Коды через запятую. Исключённые аэропорты не создают буфер.") },
                    singleLine = false,
                    minLines = 1,
                    maxLines = 3,
                    enabled = airportAvoidanceEnabled
                )
                Divider(color = colors.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Разрешить загрузку каталога", fontWeight = FontWeight.Medium)
                        Text(
                            "Отдельное согласие на один HTTPS GET публичного списка аэропортов. Координаты устройства и радиоданные не отправляются.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = airportCatalogOnlineEnabled,
                        onCheckedChange = onAirportCatalogOnlineEnabled
                    )
                }
                Text(
                    when {
                        !airportCatalogAvailable -> "Офлайн-каталог пока отсутствует. После загрузки он сохранится локально."
                        airportCatalogStale -> "Локальный кэш устарел: ${airportCatalogCount} аэропортов · ${airportCatalogSource}."
                        else -> "Локально доступно: ${airportCatalogCount} аэропортов · обновлён ${dateTime(airportCatalogUpdatedAt)}."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = onRefreshAirportCatalog,
                    enabled = airportCatalogOnlineEnabled && !airportCatalogRefreshing,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (airportCatalogRefreshing) "Загружаю каталог…" else "Загрузить / обновить офлайн-каталог")
                }
            }
        }
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
    wifiSsidBlacklist: String, onWifiSsidBlacklist: (String) -> Unit,
    detailedLogging: Boolean, diagnosticLogMaxKb: Int,
    beaconDbEnabled: Boolean, onBeaconDbEnabled: (Boolean) -> Unit,
    onDetailedLogging: (Boolean) -> Unit, onDiagnosticLogMaxKb: (Int) -> Unit,
    onViewDiagnosticLogs: () -> Unit, onClearDiagnosticLogs: () -> Unit,
    mapSource: MapSourceMode, scanIntervalSeconds: Int, adaptiveScan: Boolean, batteryProfile: BatteryProfile,
    publishLowAccuracy: Boolean, onPublishLowAccuracy: (Boolean) -> Unit,
    onMode: (PositionMode) -> Unit, onMapTheme: (MapThemeMode) -> Unit, onMapSource: (MapSourceMode) -> Unit,
    onScanInterval: (Int) -> Unit, onScanIntervalFinished: () -> Unit,
    onAdaptiveScan: (Boolean) -> Unit, onBatteryProfile: (BatteryProfile) -> Unit,
    onWifi: (Boolean) -> Unit, onCells: (Boolean) -> Unit, onBle: (Boolean) -> Unit,
    onShowPoints: (Boolean) -> Unit, onManualFallback: (Boolean) -> Unit, onManualAnchor: () -> Unit,
    onClearData: () -> Unit, onOpenLocationSettings: () -> Unit,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onExportSettingsZoneBackup: () -> Unit, onImportSettingsZoneBackup: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var showSettingsZoneBackupImportConfirm by remember { mutableStateOf(false) }
    var showMapOptions by remember { mutableStateOf(false) }
    var showRadioOptions by remember { mutableStateOf(false) }
    var showAdvancedOptions by remember { mutableStateOf(false) }
    var showDiagnosticOptions by remember { mutableStateOf(false) }
    var showDeviceCapabilities by remember { mutableStateOf(false) }
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
                    "По умолчанию выключено. При ручном поиске отправляются доступные Wi-Fi BSSID/RSSI, стабильные BLE MAC/RSSI и поддерживаемые GSM/WCDMA/LTE ID/RSSI. IP-адрес виден серверу. SSID, имена Bluetooth, payload, GPS-координаты и история не отправляются; скрытые сети и SSID с _nomap, а также private/random BLE MAC исключаются. Загрузка вкладов/наблюдений не используется.",
                    beaconDbEnabled,
                    onBeaconDbEnabled
                )
                Text(
                    "Включение не запускает запросы автоматически. BeaconDB вызывается только кнопкой во вкладке «База».",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
                Divider(color = colors.outlineVariant)
                Text("OpenCellID",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "OpenCellID не вызывается автоматически. Личный API-ключ и разрешение ручных запросов настраиваются во вкладке «База → OpenCellID». По HTTPS отправляются только текущие идентификаторы сотовых ячеек и ключ; периодические сканы используют готовый локальный кэш. Clear GPS ограничивает себя 100 попытками в UTC-день, не более 3 новых ячеек за пакет и с паузой 15 минут.",
                    style = MaterialTheme.typography.bodySmall,
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
                    Text("Фильтр SSID Wi‑Fi", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = wifiSsidBlacklist,
                        onValueChange = onWifiSsidBlacklist,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Шаблоны через запятую или с новой строки") },
                        placeholder = { Text("70mai*, guest-*") },
                        minLines = 2,
                        maxLines = 3
                    )
                    Text(
                        "По умолчанию исключаются SSID 70mai*. Поддерживаются * и ? без учёта регистра. Фильтр применяется при сохранении и позиционировании; пустое поле отключает фильтр. Уже сохранённые записи не удаляются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
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
                        "При скорости выше 25 км/ч сначала уменьшается заданный интервал. Затем применяется выбранный профиль.",
                        adaptiveScan,
                        onAdaptiveScan
                    )
                    Divider(color = colors.outlineVariant)
                    Text("Профиль энергопотребления", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    BatteryProfile.values().forEach { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onBatteryProfile(item) }.padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.Medium)
                                Text(item.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            RadioButton(selected = batteryProfile == item, onClick = { onBatteryProfile(item) })
                        }
                    }
                    Text(
                        "Профиль меняет интервал запросов фикса и повторного радиопоиска: Eco ×2, Normal — заданный интервал, Max примерно ÷2. Сырые GNSS callback-и не прореживаются, чтобы не терять данные детектора. Фактические Wi‑Fi/BLE-сканы ограничиваются Android.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                    Divider(color = colors.outlineVariant)
                    SettingSwitch(
                        "Публиковать позицию даже при очень плохой точности",
                        "Разрешает передавать свежую радиооценку с погрешностью больше 50 км. Координаты не улучшаются искусственно: приложение-получатель получает исходную большую погрешность. Устаревшие измерения и оценки менее чем по двум совпадениям по-прежнему не публикуются.",
                        publishLowAccuracy,
                        onPublishLowAccuracy
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
            title = "Возможности устройства",
            summary = "GNSS, AGC, частотные диапазоны, барометр, Wi-Fi RTT и датчики движения",
            expanded = showDeviceCapabilities,
            onClick = { showDeviceCapabilities = !showDeviceCapabilities }
        )
        if (showDeviceCapabilities) {
            DeviceCapabilitiesPanel()
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
                    Text("Работа в фоне и батарея", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "При длительном мониторинге с выключенным экраном Android или прошивка могут ограничивать работу службы. Кнопка открывает системный запрос на исключение Clear GPS из оптимизации батареи; это необязательно и может увеличить расход. Запрос появляется только после нажатия, а если прямое окно недоступно — открываются общие системные настройки батареи.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = onRequestBatteryOptimizationExemption,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(Icons.Outlined.Settings, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Настройки фоновой работы и батареи")
                    }
                    Divider(color = colors.outlineVariant)
                    Text("Приватность", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        (if (beaconDbEnabled) {
                            "BeaconDB разрешён для автообновления при активном фиксе и для ручного поиска: при изменении набора передаются текущие Wi-Fi BSSID/RSSI, стабильные BLE-адреса/RSSI и поддерживаемые сотовые ID/RSSI. Проверка службы — раз в 5 секунд, фактический радиоснимок — не чаще 30 секунд; сетевой cooldown — 2 минуты при изменении набора и 15 минут для прежнего набора. Сервер также видит IP-адрес. SSID, Bluetooth-имена/payload, GPS-координаты и история не отправляются. Скрытые и _nomap Wi-Fi, а также private/random BLE MAC исключаются; API загрузки вкладов не вызывается."
                        } else {
                            "Сетевые запросы BeaconDB выключены. Радиоидентификаторы остаются на устройстве; автоматическое и ручное обращение к сервису не выполняется."
                        }) + "\n\n" +
                        "OpenCellID автоматически проверяет новые/просроченные ячейки, когда одновременно включены его источник, сетевое разрешение, ключ и фикс. Запрашиваются только MCC/MNC/LAC или TAC/full Cell ID и личный API-ключ по HTTPS; Wi-Fi, BLE, RSSI, GPS-координаты и история не передаются. До 3 ячеек за пакет, не чаще раза в 15 минут и не более 100 попыток за UTC-день. Координаты OpenCellID — расчётное положение записи ячейки по наблюдениям сообщества, а не положение телефона или гарантированное место антенны.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Text(databaseFacts, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Divider(color = colors.outlineVariant)
                    Text("Резервная копия настроек и зон", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Файл включает режим положения, тему и источник карты, интервал, выбранные радио-источники и зоны риска с координатами. Не включает радиобазу, ручной ориентир, лимит истории, разрешение BeaconDB, активность мониторинга или mock.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onExportSettingsZoneBackup,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Экспорт") }
                        OutlinedButton(
                            onClick = { showSettingsZoneBackupImportConfirm = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Импорт") }
                    }
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
    if (showSettingsZoneBackupImportConfirm) AlertDialog(
        onDismissRequest = { showSettingsZoneBackupImportConfirm = false },
        title = { Text("Импорт настроек и зон?") },
        text = {
            Text(
                "Импорт заменит настройки режима/карты/частоты/радиоисточников и список зон риска. " +
                    "Координаты зон попадут в выбранный локальный файл. Радиобаза, ручной ориентир, разрешение BeaconDB, " +
                    "состояние мониторинга и mock не импортируются. Останови запись GNSS replay перед импортом."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                showSettingsZoneBackupImportConfirm = false
                onImportSettingsZoneBackup()
            }) { Text("Выбрать файл") }
        },
        dismissButton = {
            TextButton(onClick = { showSettingsZoneBackupImportConfirm = false }) { Text("Отмена") }
        }
    )
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
private fun HeaderCard(
    tab: AppTab,
    mode: PositionMode,
    monitorEnabled: Boolean,
    filterState: String,
    riskScore: Int,
    onCenter: () -> Unit,
    onSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val statusLabel = when {
        mode.usesRadio -> "Только радио"
        !monitorEnabled -> "Мониторинг выключен"
        filterState == FilterState.WAITING.name -> "Ждёт координаты…"
        filterState == FilterState.NORMAL.name -> "Норма"
        filterState == FilterState.SUSPECT.name -> "Подозрение"
        filterState == FilterState.ZONE_RISK.name -> "Зона риска"
        filterState == FilterState.CORRECTING.name -> "Резервная позиция"
        filterState == FilterState.NO_FALLBACK.name -> "Нет доверенной позиции"
        else -> "Анализ: мониторинг спуфинга"
    }
    val statusContainer = when {
        mode.usesRadio -> colors.secondaryContainer
        !monitorEnabled -> colors.surfaceVariant
        filterState == FilterState.SUSPECT.name || filterState == FilterState.CORRECTING.name ->
            colors.tertiaryContainer
        filterState == FilterState.ZONE_RISK.name || filterState == FilterState.NO_FALLBACK.name ->
            colors.errorContainer
        else -> colors.primaryContainer
    }
    val statusForeground = when {
        mode.usesRadio -> colors.onSecondaryContainer
        !monitorEnabled -> colors.onSurfaceVariant
        filterState == FilterState.SUSPECT.name || filterState == FilterState.CORRECTING.name ->
            colors.onTertiaryContainer
        filterState == FilterState.ZONE_RISK.name || filterState == FilterState.NO_FALLBACK.name ->
            colors.onErrorContainer
        else -> colors.onPrimaryContainer
    }
    // A persisted score is not current evidence when the monitor is stopped or GNSS is intentionally unused.
    val riskAvailable = tab == AppTab.MAP && monitorEnabled && !mode.usesRadio &&
        filterState != FilterState.WAITING.name
    val visibleScore = riskScore.coerceIn(0, 100)
    val animatedRisk by animateFloatAsState(
        targetValue = if (riskAvailable) visibleScore / 100f else 0f,
        animationSpec = tween(durationMillis = 360)
    )
    val riskColor = when {
        !riskAvailable -> colors.outlineVariant
        visibleScore >= 60 -> colors.error
        visibleScore >= 30 -> colors.tertiary
        else -> colors.primary
    }

    Surface(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(25.dp),
        color = colors.surface.copy(alpha = 0.96f),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                if (tab == AppTab.MAP) {
                    IconButton(onClick = onCenter) { Icon(Icons.Outlined.MyLocation, "Моё местоположение") }
                }
                if (tab != AppTab.SETTINGS) {
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Настройки") }
                }
            }
            if (tab == AppTab.MAP) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(statusLabel, statusContainer, statusForeground)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (riskAvailable) "Уровень риска" else if (mode.usesRadio) "Риск GNSS недоступен в режиме «Только радио»" else "Нет свежей оценки риска",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.width(6.dp))
                            Crossfade(targetState = if (riskAvailable) "$visibleScore/100" else "—") { label ->
                                Text(
                                    label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = riskColor
                                )
                            }
                        }
                        LinearProgressIndicator(
                            progress = { animatedRisk },
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                            color = riskColor,
                            trackColor = colors.surfaceVariant
                        )
                    }
                }
                Text(
                    if (riskAvailable) "Индекс риска — эвристическая оценка, не вероятность атаки."
                    else "Для актуальной оценки нужны свежие данные мониторинга GNSS.",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
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
        Crossfade(targetState = text) { animatedText ->
            Text(
                animatedText,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = foreground,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PositionSourceOption(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Surface(
        modifier = Modifier.fillMaxWidth()
            .clip(shape)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = onCheckedChange
            ),
        shape = shape,
        color = if (checked) colors.secondaryContainer else colors.surfaceContainer
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (checked) colors.primaryContainer else colors.surfaceContainerHigh
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (checked) colors.onPrimaryContainer else colors.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (checked) colors.onSecondaryContainer else colors.onSurface
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (checked) colors.onSecondaryContainer else colors.onSurfaceVariant
                )
            }
            Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapLegendBottomSheet(
    onDismiss: () -> Unit,
    mode: PositionMode,
    mapSourceMode: MapSourceMode,
    monitorEnabled: Boolean,
    mockEnabled: Boolean,
    mockOutputActive: Boolean,
    mapPointLimit: Int,
    loadedMapPointCount: Int,
    availableMapPointCount: Int,
    mapPointsLoading: Boolean,
    onMapPointLimit: (Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val mockStatus = when {
        mockOutputActive -> "Публикация координаты активна"
        monitorEnabled && mockEnabled -> "Фикс включён, пригодная координата ещё не опубликована"
        mockEnabled -> "Mock включён в настройках, мониторинг не запущен"
        else -> "Выключен"
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerHigh
    ) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Легенда и настройки карты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Значения маркеров и количество отображаемых радиоточек", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Закрыть легенду")
                }
            }

            Text("Положение и точность", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            MapLegendEntry(
                title = "Основная позиция · GPS / mock",
                description = "Синяя метка показывает текущую основную координату. Если активна публикация mock, отображается опубликованная позиция.",
                markerColor = Color(0xFF1A73E8)
            )
            MapLegendEntry(
                title = "Погрешность позиции",
                description = "Полупрозрачный синий круг — горизонтальная точность, а не граница достоверности.",
                markerColor = Color(0xFF4285F4),
                ring = true
            )
            MapLegendEntry(
                title = "Оценка по радиобазе",
                description = "Красная метка и красноватая область показывают отдельный расчёт по локальным радиоданным.",
                markerColor = Color(0xFFDC2626),
                ring = true
            )
            MapLegendEntry(
                title = "Ручной ориентир",
                description = "Янтарная метка — ориентир, поставленный пользователем; это не измерение датчика и не эталонная координата.",
                markerColor = Color(0xFFF9AB00)
            )

            Divider(Modifier.padding(vertical = 5.dp), color = colors.outlineVariant)
            Text("Радиоточки в базе", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            MapLegendEntry(
                title = "Wi‑Fi",
                description = "Зелёные точки.",
                markerColor = Color(0xFF16A34A)
            )
            MapLegendEntry(
                title = "Сотовые сети",
                description = "Фиолетовые точки.",
                markerColor = Color(0xFFA855F7)
            )
            MapLegendEntry(
                title = "Bluetooth LE",
                description = "Янтарные точки.",
                markerColor = Color(0xFFF59E0B)
            )

            Text("Количество радиоточек на карте", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                if (mapPointsLoading) {
                    "Загружаем точки… Уже загружено: $loadedMapPointCount из $availableMapPointCount с координатами."
                } else {
                    "Загружено: $loadedMapPointCount из $availableMapPointCount точек с координатами. Выбери максимальное количество маркеров, которое будет передаваться карте."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                RadioMapPointLimitPolicy.LIMIT_OPTIONS.forEach { limit ->
                    val selected = mapPointLimit == limit
                    AssistChip(
                        onClick = { onMapPointLimit(limit) },
                        enabled = !mapPointsLoading,
                        label = { Text(RadioMapPointLimitPolicy.label(limit)) },
                        colors = androidx.compose.material3.AssistChipDefaults.assistChipColors(
                            containerColor = if (selected) colors.secondaryContainer else colors.surfaceVariant,
                            labelColor = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                        )
                    )
                }
            }
            Text(
                "Настройка независима от лимита истории наблюдений. Максимум — 500 тыс. точек; большие значения увеличивают расход памяти и могут замедлять карту. Записи без координат на карту не выводятся.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )

            Text(
                if (mode.gpsOnly) {
                    "Сейчас выбран режим GPS: радио-точки скрыты на карте и не участвуют в отображении основной позиции."
                } else {
                    "В режиме «Авто» радио-точки могут отображаться вместе с GPS; в режиме «Только радио» координата рассчитывается по совпадениям с локальной базой."
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceContainer
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Происхождение данных", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Цвет точки показывает тип радио, а не происхождение. Локальные, WiGLE и другие импортированные записи используют те же цвета по типу; управление источниками и их доверием находится в «База → Источники».",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            Divider(Modifier.padding(vertical = 5.dp), color = colors.outlineVariant)
            Text("Зоны и состояние", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            MapLegendEntry(
                title = "Зона риска",
                description = "Розово-красная область и контур — сохранённая пользовательская зона риска.",
                markerColor = Color(0xFFD94B68),
                area = true
            )
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceContainer
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Mock GPS", fontWeight = FontWeight.SemiBold)
                    Text(mockStatus, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    Text(
                        "Рабочее состояние публикации показывается отдельно от наличия координаты на карте.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceContainer
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Высотные зоны и DEM", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Высотные зоны пока не отображаются отдельным слоем карты. Доступная высотная диагностика не означает, что DEM или hillshade уже нарисованы на карте.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = colors.surfaceContainer
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Источник карты", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (mapSourceMode == MapSourceMode.OFFLINE) {
                            "Офлайн-холст: внешние тайлы не загружаются."
                        } else {
                            "Онлайн-стиль OpenFreeMap: просмотр карты отправляет обычные запросы тайлов и раскрывает провайдеру IP и область карты."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun MapLegendEntry(
    title: String,
    description: String,
    markerColor: Color,
    ring: Boolean = false,
    area: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            when {
                area -> Surface(
                    modifier = Modifier.size(width = 25.dp, height = 15.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = markerColor.copy(alpha = 0.18f),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, markerColor)
                ) {}
                ring -> Surface(
                    modifier = Modifier.size(22.dp),
                    shape = CircleShape,
                    color = markerColor.copy(alpha = 0.16f),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, markerColor)
                ) {}
                else -> Surface(
                    modifier = Modifier.size(13.dp),
                    shape = CircleShape,
                    color = markerColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outline.copy(alpha = 0.4f))
                ) {}
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

private fun effectiveScanIntervalSeconds(
    configuredSeconds: Int, speedKmh: Float?, adaptive: Boolean, profile: BatteryProfile
): Int = BatteryProfilePolicy.intervalSeconds(configuredSeconds, speedKmh, adaptive, profile)

private fun currentReference(location: Location?, anchor: ManualAnchor?): RadioReference? {
    val now = System.currentTimeMillis()
    if (location != null && now - location.time in 0..60_000L && !isMockLocation(location) &&
        location.hasAccuracy() && location.accuracy in 1f..500f) {
        return RadioReference(location.latitude, location.longitude, location.accuracy.toDouble(), "android-location")
    }
    return anchor?.let { RadioReference(it.latitude, it.longitude, 1_500.0, "manual-anchor") }
}
private fun hasFineLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun hasAnyLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
private fun isMockLocation(location: Location): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider
private fun dateTime(time: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
private const val RECORD_INTERVAL_MS = 15_000L
