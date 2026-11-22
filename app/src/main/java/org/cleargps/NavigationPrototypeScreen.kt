package org.cleargps

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import android.speech.tts.TextToSpeech
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WifiOff
import android.net.NetworkCapabilities
import android.net.Network
import android.net.ConnectivityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.maps.MapView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val KEY_NAV_VOICE_ENABLED = "navigation_voice_enabled"
private const val GOOGLE_TTS_ENGINE_PACKAGE = "com.google.android.tts"

@Composable
internal fun ClearGpsNavigationShell(
    onMapViewCreated: (MapView) -> Unit,
    onMapViewDisposed: (MapView) -> Unit
) {
    var showLocationSettings by rememberSaveable { mutableStateOf(false) }

    if (showLocationSettings) {
        ClearGpsApp(
            onMapViewCreated = onMapViewCreated,
            onMapViewDisposed = onMapViewDisposed,
            onBackToNavigation = { showLocationSettings = false }
        )
    } else {
        NavigationPrototypeScreen(
            onMapViewCreated = onMapViewCreated,
            onMapViewDisposed = onMapViewDisposed,
            onOpenLocationSettings = { showLocationSettings = true }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavigationPrototypeScreen(
    onMapViewCreated: (MapView) -> Unit,
    onMapViewDisposed: (MapView) -> Unit,
    onOpenLocationSettings: () -> Unit,
    initialRouteDestination: NavigationCoordinate? = null,
    initialDestinationName: String? = null,
    onClosePreview: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val controller = remember { NavigationMapController() }
    val positionPreferences = remember {
        context.getSharedPreferences(ClearGpsPreferences.PREFS, android.content.Context.MODE_PRIVATE)
    }
    var selectedPositionMode by remember {
        mutableStateOf(
            NavigationPositionMode.fromStoredKey(
                positionPreferences.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto")
            )
        )
    }
    var navigationPosition by remember {
        mutableStateOf(readNavigationPosition(positionPreferences))
    }
    var currentSpeedKmh by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(navigationPosition?.coordinate) {
        navigationPosition?.coordinate?.let { coordinate ->
            OverpassRoadRegionQueue.enqueueCurrent(context, coordinate.latitude, coordinate.longitude)
        }
    }
    var centerOnNextPosition by remember { mutableStateOf(false) }
    var selectedMapPoint by remember { mutableStateOf<NavigationCoordinate?>(null) }
    var selectedPoi by remember { mutableStateOf<NavigationPoi?>(null) }
    var routeDestination by remember(initialRouteDestination) { mutableStateOf(initialRouteDestination) }
    var routeDestinationName by remember(initialDestinationName) { mutableStateOf(initialDestinationName) }
    val routingRepository = remember { NavigationRoutingRepository() }
    var calculatedRoutes by remember { mutableStateOf<List<NavigationRouteAlternative>>(emptyList()) }
    var selectedRouteIndex by remember { mutableStateOf(0) }
    var routeLoading by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var isNavigating by rememberSaveable { mutableStateOf(false) }
    var tripPanelExpanded by rememberSaveable { mutableStateOf(false) }
    var activeRoute by remember { mutableStateOf<NavigationRouteAlternative?>(null) }
    var guidance by remember { mutableStateOf<NavigationGuidanceSnapshot?>(null) }
    var lastAnnouncedStepIndex by remember { mutableStateOf(-1) }
    var lastAnnouncedStage by remember { mutableStateOf(-1) }
    var offRouteFixCount by remember { mutableStateOf(0) }
    var lastRerouteAtMs by remember { mutableStateOf(0L) }
    var speechEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var speechReady by remember { mutableStateOf(false) }
    var speechEnabled by remember { mutableStateOf(positionPreferences.getBoolean(KEY_NAV_VOICE_ENABLED, true)) }
    var showLocationDetails by rememberSaveable { mutableStateOf(false) }
    var showRouteOptions by rememberSaveable { mutableStateOf(false) }
    var routeOptionsExpanded by rememberSaveable { mutableStateOf(false) }
    var showMapPointSheet by rememberSaveable { mutableStateOf(false) }
    var showLocationModePicker by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var selectedNavigationTab by rememberSaveable { mutableStateOf("Карта") }
    var destinationQuery by rememberSaveable { mutableStateOf("") }
    var pendingPositionMode by remember { mutableStateOf<NavigationPositionMode?>(null) }
    var pendingCenterAfterPermission by remember { mutableStateOf(false) }

    // The red map-point marker belongs to the point sheet only. Clear it whenever
    // the sheet closes, regardless of whether it was dismissed, backed out, or
    // closed after starting route preview.
    LaunchedEffect(showMapPointSheet) {
        if (!showMapPointSheet) {
            selectedMapPoint = null
            selectedPoi = null
            controller.updateSelectedMapPoint(null)
        }
    }

    val watchedPositionKeys = remember {
        setOf(
            ClearGpsPreferences.KEY_POSITION_MODE,
            LocationMonitorService.KEY_MONITORING,
            LocationMonitorService.KEY_HAS_OUTPUT,
            LocationMonitorService.KEY_SOURCE,
            LocationMonitorService.KEY_LAT,
            LocationMonitorService.KEY_LON,
            LocationMonitorService.KEY_ACCURACY,
            LocationMonitorService.KEY_BEARING,
            LocationMonitorService.KEY_LAST_OBSERVED_GPS_LAT,
            LocationMonitorService.KEY_LAST_OBSERVED_GPS_LON,
            LocationMonitorService.KEY_LAST_OBSERVED_GPS_ACCURACY,
            LocationMonitorService.KEY_LAST_OBSERVED_GPS_ELAPSED_NANOS,
            LocationMonitorService.KEY_LAST_OBSERVED_NETWORK_LAT,
            LocationMonitorService.KEY_LAST_OBSERVED_NETWORK_LON,
            LocationMonitorService.KEY_LAST_OBSERVED_NETWORK_ACCURACY,
            LocationMonitorService.KEY_LAST_OBSERVED_NETWORK_ELAPSED_NANOS
        )
    }
    DisposableEffect(positionPreferences) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in watchedPositionKeys) {
                selectedPositionMode = NavigationPositionMode.fromStoredKey(
                    positionPreferences.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto")
                )
                navigationPosition = readNavigationPosition(positionPreferences)
            }
        }
        positionPreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { positionPreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // Keep the map in sync with the monitor even if Android batches/drops a preference callback.
    // This is especially important after the user explicitly requests a fresh GPS fix.
    LaunchedEffect(positionPreferences) {
        while (true) {
            val latestMode = NavigationPositionMode.fromStoredKey(
                positionPreferences.getString(ClearGpsPreferences.KEY_POSITION_MODE, "auto")
            )
            val latestPosition = readNavigationPosition(positionPreferences)
            if (latestMode != selectedPositionMode) selectedPositionMode = latestMode
            if (latestPosition != navigationPosition) navigationPosition = latestPosition
            currentSpeedKmh = Obd2SharedState.freshSpeedKmh(positionPreferences) ?: latestPosition?.speedKmh
            delay(500L)
        }
    }

    DisposableEffect(controller) {
        controller.setOnMapTapListener { point ->
            selectedPoi = null
            selectedMapPoint = point
            controller.updateSelectedMapPoint(point)
            showMapPointSheet = true
        }
        controller.setOnRouteTapListener { index ->
            if (!isNavigating && index in calculatedRoutes.indices) {
                selectedRouteIndex = index
            }
        }
        controller.setOnLocationTapListener { showLocationDetails = true }
        controller.setOnPoiTapListener { poi ->
            selectedPoi = poi
            selectedMapPoint = poi.coordinate
            controller.updateSelectedMapPoint(poi.coordinate)
            showMapPointSheet = true
        }
        onDispose {
            controller.setOnMapTapListener(null)
            controller.setOnRouteTapListener(null)
            controller.setOnLocationTapListener(null)
            controller.setOnPoiTapListener(null)
        }
    }

    // The Google TTS service exists only while voice guidance is enabled.
    DisposableEffect(context, speechEnabled) {
        if (!speechEnabled) {
            speechEngine?.stop()
            speechEngine?.shutdown()
            speechEngine = null
            speechReady = false
            onDispose { }
        } else {
            var createdEngine: TextToSpeech? = null
            try {
                lateinit var engine: TextToSpeech
                engine = TextToSpeech(context, { status ->
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (speechEngine !== engine || !speechEnabled) return@post
                        if (status == TextToSpeech.SUCCESS) {
                            val language = engine.setLanguage(Locale("ru", "RU"))
                            speechReady = language >= TextToSpeech.LANG_AVAILABLE
                            if (!speechReady) {
                                DiagnosticLogStore.log(context, "NAV_VOICE", "Google Text-to-Speech не поддерживает русский язык на этом устройстве.", "WARN")
                            }
                        } else {
                            speechReady = false
                            DiagnosticLogStore.log(context, "NAV_VOICE", "Не удалось инициализировать Google Text-to-Speech: status=" + status, "WARN")
                            Toast.makeText(context, "Google Text-to-Speech недоступен", Toast.LENGTH_LONG).show()
                        }
                    }
                }, GOOGLE_TTS_ENGINE_PACKAGE)
                createdEngine = engine
                speechEngine = engine
                speechReady = false
            } catch (error: Exception) {
                speechEngine = null
                speechReady = false
                DiagnosticLogStore.log(context, "NAV_VOICE", "Ошибка запуска Google Text-to-Speech: " + error.javaClass.simpleName + ": " + (error.message ?: "без описания"), "ERROR")
                Toast.makeText(context, "Не удалось запустить Google Text-to-Speech", Toast.LENGTH_LONG).show()
            }
            onDispose {
                createdEngine?.let { engine ->
                    if (speechEngine === engine) {
                        speechEngine = null
                        speechReady = false
                    }
                    engine.stop()
                    engine.shutdown()
                }
            }
        }
    }

    LaunchedEffect(navigationPosition) {
        controller.updatePosition(navigationPosition)
        val fix = navigationPosition
        DiagnosticLogStore.log(
            context,
            "NAV_POSITION",
            if (fix == null) "Позиция ClearGPS отсутствует"
            else "Позиция обновлена: accuracy_m=${fix.accuracyMeters}, bearing_deg=${fix.bearingDegrees}; координаты скрыты"
        )
    }

    LaunchedEffect(navigationPosition, isNavigating, activeRoute, routeDestination, speechReady, speechEnabled) {
        if (!isNavigating) return@LaunchedEffect
        val position = navigationPosition?.coordinate ?: return@LaunchedEffect
        val route = activeRoute ?: return@LaunchedEffect
        val progress = NavigationGuidanceEngine.snapshot(
            position = position,
            route = route,
            previousStepIndex = guidance?.stepIndex ?: 0
        ) ?: return@LaunchedEffect
        guidance = progress
        controller.followNavigationPosition(position, navigationPosition?.bearingDegrees)
        val stepChanged = progress.stepIndex != lastAnnouncedStepIndex
        val announcementStage = when {
            progress.distanceToManeuverMeters > 150.0 -> 2
            progress.distanceToManeuverMeters > 40.0 -> 1
            else -> 0
        }
        if (stepChanged) {
            lastAnnouncedStepIndex = progress.stepIndex
            lastAnnouncedStage = 3
        }
        if (speechEnabled && speechReady && (stepChanged || announcementStage < lastAnnouncedStage)) {
            val spokenInstruction = when (announcementStage) {
                2 -> "Через ${formatRouteDistance(progress.distanceToManeuverMeters)}. ${progress.instruction}"
                1 -> "Скоро. ${progress.instruction}"
                else -> progress.instruction
            }
            lastAnnouncedStage = announcementStage
            DiagnosticLogStore.log(
                context,
                "NAV_VOICE",
                "Озвучивание манёвра: step=${progress.stepIndex}, stage=$announcementStage, distance_m=${progress.distanceToManeuverMeters.toInt()}, instruction=${progress.instruction}"
            )
            speechEngine?.speak(
                spokenInstruction,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "cleargps-maneuver-${progress.stepIndex}-${announcementStage}"
            )
        }
        val accuracy = navigationPosition?.accuracyMeters?.coerceIn(0.0, 300.0) ?: 0.0
        val offRouteLimit = maxOf(45.0, accuracy * 1.5)
        if (progress.offRouteDistanceMeters > offRouteLimit) offRouteFixCount += 1
        else offRouteFixCount = 0

        val now = android.os.SystemClock.elapsedRealtime()
        val destination = routeDestination
        if (offRouteFixCount >= 3 && destination != null && now - lastRerouteAtMs >= 15_000L) {
            lastRerouteAtMs = now
            offRouteFixCount = 0
            DiagnosticLogStore.log(
                context,
                "NAV_REROUTE",
                "Запрошено перестроение: отклонение_m=${progress.offRouteDistanceMeters.toInt()}, порог_m=${offRouteLimit.toInt()}"
            )
            routingRepository.findRoutes(position, destination) { result ->
                result.onSuccess { alternatives ->
                    val fastest = alternatives.minByOrNull { it.durationSeconds } ?: return@onSuccess
                    calculatedRoutes = alternatives
                    selectedRouteIndex = alternatives.indexOf(fastest).coerceAtLeast(0)
                    activeRoute = fastest
                    guidance = null
                    lastAnnouncedStepIndex = -1
                    lastAnnouncedStage = -1
                    controller.updateRoutes(listOf(fastest))
                    DiagnosticLogStore.log(context, "NAV_REROUTE", "Перестроение успешно: вариантов=${alternatives.size}, дистанция_m=${fastest.distanceMeters.toInt()}, время_s=${fastest.durationSeconds.toInt()}")
                    Toast.makeText(context, "Маршрут обновлён после отклонения", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    DiagnosticLogStore.log(context, "NAV_REROUTE", "Ошибка перестроения: ${error.javaClass.simpleName}: ${error.message ?: "без описания"}", "ERROR")
                    Toast.makeText(context, "Не удалось перестроить маршрут: ${error.message ?: "ошибка сети"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    LaunchedEffect(routeDestination) {
        controller.updateDestination(routeDestination)
    }
    LaunchedEffect(isNavigating, activeRoute) { controller.setNavigationActive(isNavigating, activeRoute) }
    LaunchedEffect(calculatedRoutes, selectedRouteIndex) {
        controller.updateRoutes(calculatedRoutes, selectedRouteIndex)
    }

    LaunchedEffect(routeDestination, if (isNavigating) null else navigationPosition?.coordinate) {
        if (isNavigating) return@LaunchedEffect
        calculatedRoutes = emptyList()
        selectedRouteIndex = 0
        routeError = null
        controller.updateRoutes(emptyList())
        val destination = routeDestination
        val origin = navigationPosition?.coordinate
        if (destination != null) {
            if (origin == null) {
                routeError = "Нет текущих координат. Сначала получите местоположение ClearGPS."
            } else {
                routeLoading = true
                DiagnosticLogStore.log(context, "NAV_ROUTE", "Запрошен расчёт маршрута; координаты начала и назначения скрыты")
                routingRepository.findRoutes(origin, destination) { result ->
                    routeLoading = false
                    result.onSuccess { alternatives ->
                        calculatedRoutes = alternatives.take(3)
                        selectedRouteIndex = 0
                        controller.updateRoutes(calculatedRoutes)
                        DiagnosticLogStore.log(context, "NAV_ROUTE", "Маршрут рассчитан: получено вариантов=${alternatives.size}, отображается=${calculatedRoutes.size}, дистанция_m=${calculatedRoutes.firstOrNull()?.distanceMeters?.toInt()}, время_s=${calculatedRoutes.firstOrNull()?.durationSeconds?.toInt()}")
                    }.onFailure { error ->
                        routeError = error.message ?: "Не удалось построить маршрут"
                        DiagnosticLogStore.log(context, "NAV_ROUTE", "Ошибка расчёта: ${error.javaClass.simpleName}: ${error.message ?: "без описания"}", "ERROR")
                    }
                }
            }
        } else {
            routeLoading = false
        }
    }
    DisposableEffect(routingRepository) {
        onDispose { routingRepository.cancel() }
    }
    LaunchedEffect(navigationPosition, centerOnNextPosition) {
        if (centerOnNextPosition && navigationPosition != null) {
            controller.centerOnCurrentPosition()
            centerOnNextPosition = false
        }
    }
    fun commitPositionMode(mode: NavigationPositionMode) {
        val monitoringWasActive = positionPreferences.getBoolean(
            LocationMonitorService.KEY_MONITORING,
            false
        )
        val error = applyNavigationPositionMode(context, mode)
        selectedPositionMode = mode
        navigationPosition = readNavigationPosition(positionPreferences)
        showLocationModePicker = false
        centerOnNextPosition = true
        when {
            error != null -> Toast.makeText(
                context,
                "Не удалось применить режим: $error",
                Toast.LENGTH_LONG
            ).show()
            !monitoringWasActive -> Toast.makeText(
                context,
                "Режим сохранён. Включите мониторинг ClearGPS в настройках, чтобы получать координаты.",
                Toast.LENGTH_LONG
            ).show()
            else -> Toast.makeText(
                context,
                "Режим «${mode.title}»: ожидаю новый расчёт ClearGPS.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun startLocationAcquisition() {
        centerOnNextPosition = true
        navigationPosition = readNavigationPosition(positionPreferences)
        // Re-enable follow mode immediately when we already have a usable fix; if not,
        // the pending flag above will center the camera as soon as the next fix is published.
        controller.centerOnCurrentPosition()
        val intent = android.content.Intent(context, LocationMonitorService::class.java)
            .setAction(LocationMonitorService.ACTION_LOCATE)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Toast.makeText(
                context,
                "Запускаю расчёт местоположения («${selectedPositionMode.title}»). Карта переместится после получения координат.",
                Toast.LENGTH_SHORT
            ).show()
        } catch (error: Exception) {
            centerOnNextPosition = false
            Toast.makeText(
                context,
                "Не удалось запустить геолокацию: ${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val locationActionPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (pendingCenterAfterPermission) {
            pendingCenterAfterPermission = false
            if (fineGranted) {
                startLocationAcquisition()
            } else {
                Toast.makeText(
                    context,
                    "Для выбранного источника разрешите точное местоположение.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun requestMapLocation() {
        val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fineGranted) {
            startLocationAcquisition()
        } else {
            pendingCenterAfterPermission = true
            locationActionPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val positionModePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val requestedMode = pendingPositionMode
        pendingPositionMode = null
        if (requestedMode != null) {
            val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val allowed = fineGranted
            if (allowed) {
                commitPositionMode(requestedMode)
            } else {
                Toast.makeText(
                    context,
                    when (requestedMode) {
                        NavigationPositionMode.GPS_ONLY ->
                            "Для обычного GPS необходимо разрешение «Точное местоположение»."
                        NavigationPositionMode.RADIO_ONLY ->
                            "Для радиопозиционирования необходимо разрешение «Точное местоположение»."
                        NavigationPositionMode.AUTO ->
                            "Для режима «Авто» требуется разрешение «Точное местоположение», чтобы получать GPS."
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun selectPositionMode(mode: NavigationPositionMode) {
        val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val needsPermission = !fineGranted
        if (needsPermission) {
            pendingPositionMode = mode
            positionModePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            commitPositionMode(mode)
        }
    }

    fun stepZoom(delta: Double) {
        controller.zoomBy(delta)
    }

    ClearGpsTheme(dark = systemDark) {
        val colors = MaterialTheme.colorScheme
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
        ) {
            AndroidView(
                factory = { viewContext ->
                    MapView(viewContext).also { view ->
                        onMapViewCreated(view)
                        controller.attach(
                            mapView = view,
                            context = viewContext,
                            initialDarkMode = systemDark,
                            initialPosition = navigationPosition
                        )
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { view ->
                    controller.detach(view)
                    onMapViewDisposed(view)
                }
            )

            if (routeDestination == null) {
            // Top search island.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp)
                    .shadow(7.dp, RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                color = colors.surfaceContainerHigh.copy(alpha = 0.97f),
                tonalElevation = 3.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedNavigationTab = "Поиск"
                            showSearch = true
                        }.padding(start = 15.dp, end = 7.dp, top = 7.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = "Поиск места", tint = colors.primary)
                        Column(
                            modifier = Modifier.weight(1f).padding(start = 12.dp, top = 3.dp, bottom = 3.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text(if (destinationQuery.isBlank()) "Куда едем?" else destinationQuery,
                                style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
                            Text("Адрес или название места", style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            selectedNavigationTab = "Настройки"
                            showSearch = false
                            showSettings = true
                        }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Настройки навигации", tint = colors.onSurface)
                        }
                    }
                    NavigationStatusStrip(
                        hasInternet = rememberValidatedInternet(context),
                        position = navigationPosition,
                        positionMode = selectedPositionMode,
                        routeLoading = routeLoading,
                        routeError = routeError
                    )
                }
            }


            }

            if (routeDestination != null && isNavigating) {
                NavigationSpeedBadges(
                    currentSpeedKmh = currentSpeedKmh,
                    speedLimitKmh = null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 14.dp)
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 285.dp)
                )
            }

            if (routeDestination != null && isNavigating) {
                val maneuver = guidance
                val instruction = maneuver?.instruction ?: "Определяем следующий манёвр"
                val maneuverGlyph = when {
                    instruction.contains("налево", ignoreCase = true) -> "↰"
                    instruction.contains("направо", ignoreCase = true) -> "↱"
                    instruction.contains("разворот", ignoreCase = true) -> "⤴"
                    instruction.contains("кругов", ignoreCase = true) -> "⟳"
                    instruction.contains("съезд", ignoreCase = true) -> "↗"
                    else -> "↑"
                }
                val motion = rememberInfiniteTransition(label = "maneuver-motion")
                val arrowScale by motion.animateFloat(
                    initialValue = 0.92f,
                    targetValue = 1.08f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 850),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "arrow-pulse"
                )
                val haloAlpha by motion.animateFloat(
                    initialValue = 0.18f,
                    targetValue = 0.52f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 850),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "arrow-halo"
                )
                val travelPhase by motion.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1700),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "route-flow"
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = maneuver != null) {
                            maneuver?.maneuverCoordinate?.let(controller::focusOnManeuver)
                        }
                        .padding(horizontal = 14.dp)
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 91.dp)
                        .shadow(12.dp, RoundedCornerShape(24.dp)),
                    shape = RoundedCornerShape(24.dp),
                    color = colors.primaryContainer.copy(alpha = 0.99f),
                    tonalElevation = 7.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize(animationSpec = tween(280))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(13.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .size(42.dp)
                                        .graphicsLayer {
                                            scaleX = 0.92f + arrowScale * 0.12f
                                            scaleY = 0.92f + arrowScale * 0.12f
                                            alpha = haloAlpha
                                        },
                                    shape = RoundedCornerShape(20.dp),
                                    color = colors.primary.copy(alpha = 0.35f)
                                ) {}
                                Surface(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .graphicsLayer {
                                            scaleX = arrowScale
                                            scaleY = arrowScale
                                        },
                                    shape = RoundedCornerShape(17.dp),
                                    color = colors.primary
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        AnimatedContent(
                                            targetState = maneuverGlyph,
                                            transitionSpec = {
                                                (fadeIn(tween(180)) + slideInVertically { it / 2 }) togetherWith
                                                    (fadeOut(tween(120)) + slideOutVertically { -it / 3 })
                                            },
                                            label = "maneuver-arrow"
                                        ) { glyph ->
                                            Text(
                                                glyph,
                                                style = MaterialTheme.typography.headlineLarge,
                                                color = colors.onPrimary
                                            )
                                        }
                                    }
                                }
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                AnimatedContent(
                                    targetState = instruction,
                                    transitionSpec = {
                                        (fadeIn(tween(220)) + slideInVertically { it / 3 }) togetherWith
                                            (fadeOut(tween(140)) + slideOutVertically { -it / 4 })
                                    },
                                    label = "maneuver-instruction"
                                ) { currentInstruction ->
                                    Text(
                                        currentInstruction,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = colors.onPrimaryContainer,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 5.dp)
                                .padding(horizontal = 15.dp)
                                .padding(bottom = 10.dp)
                                .background(colors.onPrimaryContainer.copy(alpha = 0.12f), CircleShape)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.22f)
                                    .heightIn(min = 5.dp)
                                    .graphicsLayer {
                                        translationX = size.width * travelPhase * 3.5f
                                    }
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                colors.primary.copy(alpha = 0.18f),
                                                colors.primary,
                                                colors.primary.copy(alpha = 0.18f)
                                            )
                                        ),
                                        CircleShape
                                    )
                            )
                        }
                    }
                }
            }

            // Smooth zoom controls; no slider.
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp, bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = colors.surfaceContainerHigh.copy(alpha = 0.97f),
                    tonalElevation = 3.dp,
                    shadowElevation = 5.dp
                ) {
                    IconButton(onClick = { stepZoom(1.0) }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Приблизить карту")
                    }
                }
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = CircleShape,
                    color = colors.surfaceContainerHigh.copy(alpha = 0.97f),
                    tonalElevation = 3.dp,
                    shadowElevation = 5.dp
                ) {
                    IconButton(onClick = { stepZoom(-1.0) }) {
                        Icon(Icons.Outlined.Remove, contentDescription = "Отдалить карту")
                    }
                }
            }

            // OpenStreetMap attribution for POI data (ODbL).
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 12.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 178.dp
                    ),
                shape = RoundedCornerShape(8.dp),
                color = colors.surfaceContainerHigh.copy(alpha = 0.92f),
                tonalElevation = 2.dp
            ) {
                Text(
                    "© OpenStreetMap contributors · ODbL",
                    modifier = Modifier
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://www.openstreetmap.org/copyright")
                                    )
                                )
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }

            // Location control stays in the lower-right corner, above the bottom navigation panel.
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = 12.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 176.dp
                    ),
                shape = CircleShape,
                color = colors.surfaceContainerHigh.copy(alpha = 0.97f),
                tonalElevation = 3.dp,
                shadowElevation = 5.dp
            ) {
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .combinedClickable(
                            onClick = { requestMapLocation() },
                            onLongClick = { showLocationModePicker = true }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.MyLocation,
                        contentDescription = "Моё местоположение; удерживайте для выбора источника",
                        tint = if (navigationPosition != null) colors.primary else colors.onSurfaceVariant
                    )
                }
            }

            if (routeDestination == null) {
            // Route summary and compact floating navigation island.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 10.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier
                        .widthIn(min = 270.dp, max = 340.dp)
                        .shadow(8.dp, RoundedCornerShape(28.dp)),
                    shape = RoundedCornerShape(28.dp),
                    color = colors.surfaceContainerHigh.copy(alpha = 0.98f),
                    tonalElevation = 5.dp
                ) {
                    Row(
                        modifier = Modifier.padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    selectedNavigationTab = "Карта"
                                    showSettings = false
                                    showSearch = false
                                },
                            shape = RoundedCornerShape(20.dp),
                            color = if (selectedNavigationTab == "Карта") {
                                colors.secondaryContainer
                            } else {
                                Color.Transparent
                            }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 7.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Map,
                                    contentDescription = "Карта",
                                    modifier = Modifier.size(20.dp),
                                    tint = if (selectedNavigationTab == "Карта") {
                                        colors.onSecondaryContainer
                                    } else {
                                        colors.onSurfaceVariant
                                    }
                                )
                                Text(
                                    "Карта",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedNavigationTab == "Карта") {
                                        colors.onSecondaryContainer
                                    } else {
                                        colors.onSurfaceVariant
                                    }
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    selectedNavigationTab = "Поиск"
                                    showSettings = false
                                    showSearch = true
                                },
                            shape = RoundedCornerShape(20.dp),
                            color = if (selectedNavigationTab == "Поиск") {
                                colors.secondaryContainer
                            } else {
                                Color.Transparent
                            }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 7.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = "Поиск",
                                    modifier = Modifier.size(20.dp),
                                    tint = if (selectedNavigationTab == "Поиск") {
                                        colors.onSecondaryContainer
                                    } else {
                                        colors.onSurfaceVariant
                                    }
                                )
                                Text(
                                    "Поиск",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedNavigationTab == "Поиск") {
                                        colors.onSecondaryContainer
                                    } else {
                                        colors.onSurfaceVariant
                                    }
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    selectedNavigationTab = "Настройки"
                                    showSearch = false
                                    showSettings = true
                                },
                            shape = RoundedCornerShape(20.dp),
                            color = if (selectedNavigationTab == "Настройки") {
                                colors.secondaryContainer
                            } else {
                                Color.Transparent
                            }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 7.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = "Настройки",
                                    modifier = Modifier.size(20.dp),
                                    tint = if (selectedNavigationTab == "Настройки") {
                                        colors.onSecondaryContainer
                                    } else {
                                        colors.onSurfaceVariant
                                    }
                                )
                                Text(
                                    "Настройки",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selectedNavigationTab == "Настройки") {
                                        colors.onSecondaryContainer
                                    } else {
                                        colors.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }
            }


            }
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 13.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 178.dp
                    ),
                shape = RoundedCornerShape(9.dp),
                color = colors.surfaceContainerHigh.copy(alpha = 0.92f)
            ) {
                Text(
                    "© OpenFreeMap · © OpenStreetMap",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
            if (routeDestination != null && !isNavigating) {
                val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val chosenRoute = calculatedRoutes.getOrNull(selectedRouteIndex)
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = bottomInset + 10.dp).shadow(10.dp, RoundedCornerShape(26.dp)), shape = RoundedCornerShape(26.dp), color = colors.surfaceContainerHigh.copy(alpha = 0.99f), tonalElevation = 5.dp) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.align(Alignment.CenterHorizontally).size(width = 38.dp, height = 4.dp).background(colors.outlineVariant, CircleShape))
                        if (routeLoading) Text("Рассчитываю маршруты…", style = MaterialTheme.typography.titleMedium)
                        else if (routeError != null) Text(routeError!!, style = MaterialTheme.typography.bodyMedium, color = colors.error)
                        else if (chosenRoute != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Лучший маршрут", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                                    Text("${formatRouteDuration(chosenRoute.durationSeconds)}  ·  ${formatRouteDistance(chosenRoute.distanceMeters)}", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                                    Text("Прибытие около ${java.time.LocalTime.now().plusSeconds(chosenRoute.durationSeconds.toLong().coerceAtLeast(0L)).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                                TextButton(onClick = { routeOptionsExpanded = !routeOptionsExpanded }) { Text(if (routeOptionsExpanded) "Свернуть" else "Все ${calculatedRoutes.size} маршрута") }
                            }
                            if (routeOptionsExpanded) calculatedRoutes.forEachIndexed { index, route ->
                                val selected = index == selectedRouteIndex
                                val isFastest = route.durationSeconds == calculatedRoutes.minOf { it.durationSeconds }
                                Surface(Modifier.fillMaxWidth().clickable { selectedRouteIndex = index }, shape = RoundedCornerShape(16.dp), color = if (selected) colors.secondaryContainer else colors.surfaceContainer) {
                                    Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = selected, onClick = { selectedRouteIndex = index })
                                        Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(if (isFastest) "Самый быстрый" else "Вариант ${index + 1}", style = MaterialTheme.typography.titleSmall, color = if (selected) colors.onSecondaryContainer else colors.onSurface)
                                            Text("${formatRouteDuration(route.durationSeconds)}  ·  ${formatRouteDistance(route.distanceMeters)}", style = MaterialTheme.typography.bodySmall, color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant)
                                        }
                                        if (selected) Text("Выбран", style = MaterialTheme.typography.labelSmall, color = colors.primary)
                                    }
                                }
                            }
                            Button(onClick = {
                                DiagnosticLogStore.log(context, "NAV_SESSION", "Начато ведение: дистанция_m=${chosenRoute.distanceMeters.toInt()}, время_s=${chosenRoute.durationSeconds.toInt()}")
                                activeRoute = chosenRoute
                                isNavigating = true
                                tripPanelExpanded = false
                                guidance = null
                                lastAnnouncedStepIndex = -1
                                lastAnnouncedStage = -1
                                offRouteFixCount = 0
                                lastRerouteAtMs = 0L
                                routeOptionsExpanded = false
                                controller.updateRoutes(listOf(chosenRoute))
                                navigationPosition?.coordinate?.let { controller.followNavigationPosition(it, navigationPosition?.bearingDegrees) }
                            }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.Navigation, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("Поехали")
                            }
                        } else Text("Ожидаю расчёт маршрута…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (routeDestination != null && isNavigating) {
                NavigationTripPanel(
                    route = activeRoute,
                    progress = guidance,
                    expanded = tripPanelExpanded,
                    onExpandedChange = { tripPanelExpanded = it },
                    onFinish = {
                        DiagnosticLogStore.log(context, "NAV_SESSION", "Ведение по маршруту завершено пользователем")
                        isNavigating = false
                        tripPanelExpanded = false
                        activeRoute = null
                        guidance = null
                        lastAnnouncedStepIndex = -1
                        lastAnnouncedStage = -1
                        offRouteFixCount = 0
                        controller.updateRoutes(calculatedRoutes)
                        controller.centerOnCurrentPosition()
                    }
                )
            }

        }

        BackHandler(enabled = showSettings || showLocationDetails || showLocationModePicker || showMapPointSheet || showRouteOptions || routeOptionsExpanded || routeDestination != null) {
            when {
                showRouteOptions -> showRouteOptions = false
                showLocationDetails -> showLocationDetails = false
                routeOptionsExpanded -> routeOptionsExpanded = false
                showMapPointSheet -> showMapPointSheet = false
                routeDestination != null -> {
                    if (onClosePreview != null) onClosePreview()
                    else {
                        routeDestination = null
                        routeDestinationName = null
                        calculatedRoutes = emptyList()
                        controller.updateDestination(null)
                        controller.updateRoutes(emptyList())
                    }
                }
                showSettings -> {
                    showSettings = false
                    selectedNavigationTab = "Карта"
                }
                else -> showLocationModePicker = false
            }
        }


        if (showLocationDetails) {
            val fix = navigationPosition
            val monitoring = positionPreferences.getBoolean(LocationMonitorService.KEY_MONITORING, false)
            val outputActive = positionPreferences.getBoolean(LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE, false)
            val hasOutput = positionPreferences.getBoolean(LocationMonitorService.KEY_HAS_OUTPUT, false)
            val source = fix?.source ?: positionPreferences.getString(LocationMonitorService.KEY_SOURCE, "NONE").orEmpty()
            val observedElapsed = when {
                source.contains("NETWORK") -> positionPreferences.getLong(LocationMonitorService.KEY_LAST_OBSERVED_NETWORK_ELAPSED_NANOS, 0L)
                source.contains("GPS") || source == "LAST_TRUSTED" -> positionPreferences.getLong(LocationMonitorService.KEY_LAST_OBSERVED_GPS_ELAPSED_NANOS, 0L)
                else -> 0L
            }
            val ageSeconds = if (observedElapsed > 0L) ((android.os.SystemClock.elapsedRealtimeNanos() - observedElapsed).coerceAtLeast(0L) / 1_000_000_000L) else null
            ModalBottomSheet(onDismissRequest = { showLocationDetails = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Моё местоположение", style = MaterialTheme.typography.headlineSmall)
                    Text(fix?.let { when (it.source.uppercase()) { "GPS" -> "Физический GPS · прошло фильтр ClearGPS"; "GPS_UNVERIFIED" -> "GPS · не прошло проверку фильтра"; "GPS_UNVERIFIED_STALE" -> "Старая GPS-позиция · не проверена"; "NETWORK", "NETWORK_UNVERIFIED" -> "Сеть Android · приблизительная оценка"; "LOCAL_RADIO", "LOCAL_RADIO_STALE" -> "Радиопозиция ClearGPS"; "BEACONDB" -> "Оценка BeaconDB"; "OBD2_DEAD_RECKONING" -> "Прогноз GPS + OBD-II"; else -> it.source } } ?: "Позиция ClearGPS отсутствует", style = MaterialTheme.typography.titleMedium)
                    Text("Режим: ${selectedPositionMode.title}", style = MaterialTheme.typography.bodyMedium)
                    Text("Источник: $source", style = MaterialTheme.typography.bodyMedium)
                    Text("Точность: ${fix?.accuracyMeters?.let { if (it >= 1000.0) "±%.2f км".format(Locale.getDefault(), it / 1000.0) else "±${it.toInt()} м" } ?: "неизвестна"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Курс движения: ${fix?.bearingDegrees?.let { "%.0f°".format(Locale.getDefault(), it) } ?: "нет данных"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Последняя фиксация источника: ${ageSeconds?.let { if (it < 60) "$it сек. назад" else "${it / 60} мин. назад" } ?: "время недоступно для этого источника"}", style = MaterialTheme.typography.bodyMedium)
                    fix?.coordinate?.let { Text("Координаты: %.6f, %.6f".format(Locale.US, it.latitude, it.longitude), style = MaterialTheme.typography.bodySmall) }
                    Text("Мониторинг: ${if (monitoring) "включён" else "выключен"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Позиция опубликована для Android: ${if (hasOutput) "да" else "нет"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Mock-провайдеры ClearGPS: ${if (outputActive) "активны" else "не активны"}", style = MaterialTheme.typography.bodyMedium)
                    Text("Это фактическое состояние, сохранённое ClearGPS. Другие приложения могут выбирать другой Android-провайдер или игнорировать mock-координаты.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    TextButton(onClick = { showLocationDetails = false }, modifier = Modifier.align(Alignment.End)) { Text("Закрыть") }
                }
            }
        }

        val selectedPointForSheet = selectedMapPoint
        if (showMapPointSheet && selectedPointForSheet != null) {
            val poiForSheet = selectedPoi
            ModalBottomSheet(
                onDismissRequest = { showMapPointSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = colors.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        poiForSheet?.name ?: "Точка на карте",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    if (poiForSheet != null) {
                        Text(
                            if (poiForSheet.tags["cleargps:address_only"] == "yes") "Адрес здания" else poiForSheet.category.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary
                        )
                        poiForSheet.address?.let { address ->
                            Text(address, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        val knownTagKeys = setOf(
                            "name", "brand", "opening_hours", "phone", "contact:phone",
                            "website", "contact:website", "url", "email", "contact:email",
                            "cuisine", "operator", "wheelchair", "addr:street",
                            "addr:housenumber", "addr:city", "addr:postcode", "addr:district",
                            "addr:suburb", "addr:country", "addr:state", "addr:province",
                            "building", "building:levels", "building:flats", "addr:unit",
                            "addr:floor", "entrance"
                        )
                        val extraTagRows = poiForSheet.tags
                            .filter { (key, value) -> key !in knownTagKeys && value.isNotBlank() }
                            .toSortedMap()
                            .map { (key, value) -> "OSM · $key" to value }
                        val addressRows = if (poiForSheet.tags["cleargps:address_only"] == "yes") {
                            listOfNotNull(
                                poiForSheet.tags["addr:street"]?.let { "Улица" to it },
                                poiForSheet.tags["addr:housenumber"]?.let { "Номер дома" to it },
                                poiForSheet.tags["addr:postcode"]?.let { "Индекс" to it },
                                poiForSheet.tags["addr:city"]?.let { "Город" to it },
                                poiForSheet.tags["addr:district"]?.let { "Район" to it },
                                poiForSheet.tags["addr:suburb"]?.let { "Микрорайон" to it },
                                poiForSheet.tags["addr:state"]?.let { "Регион" to it },
                                poiForSheet.tags["addr:country"]?.let { "Страна" to it },
                                poiForSheet.tags["building"]?.let { "Тип здания" to it },
                                poiForSheet.tags["building:levels"]?.let { "Этажей" to it },
                                poiForSheet.tags["building:flats"]?.let { "Квартир" to it },
                                poiForSheet.tags["addr:unit"]?.let { "Помещение" to it },
                                poiForSheet.tags["addr:floor"]?.let { "Этаж" to it },
                                poiForSheet.tags["entrance"]?.let { "Вход" to it }
                            )
                        } else emptyList()
                        val detailRows = addressRows + listOfNotNull(
                            poiForSheet.openingHours?.let { "Часы работы" to it },
                            poiForSheet.phone?.let { "Телефон" to it },
                            poiForSheet.website?.let { "Сайт" to it },
                            poiForSheet.email?.let { "Эл. почта" to it },
                            poiForSheet.cuisine?.let { "Кухня" to it },
                            poiForSheet.operator?.let { "Оператор" to it },
                            poiForSheet.wheelchair?.let {
                                "Доступность" to when (it) {
                                    "yes" -> "Доступно для инвалидных колясок"
                                    "no" -> "Нет доступа для инвалидных колясок"
                                    "limited" -> "Ограниченная доступность"
                                    else -> it
                                }
                            }
                        ) + extraTagRows
                        if (detailRows.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                detailRows.forEach { (label, value) ->
                                    val isWebsite = label == "Сайт"
                                    Text(
                                        "$label: $value",
                                        modifier = if (isWebsite) Modifier.clickable {
                                            val url = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
                                            runCatching { uriHandler.openUri(url) }
                                        } else Modifier,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isWebsite) colors.primary else colors.onSurface
                                    )
                                }
                            }
                        } else {
                            Text(
                                "Дополнительные сведения не указаны в OpenStreetMap.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    } else {
                        Text(
                            "Выбрана точка, по которой вы нажали на карте.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Text(
                        "${String.format(java.util.Locale.US, "%.6f", selectedPointForSheet.latitude)}, " +
                            String.format(java.util.Locale.US, "%.6f", selectedPointForSheet.longitude),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface
                    )
                    Button(
                        onClick = {
                            val destinationName = poiForSheet?.name ?: "Точка на карте"
                            if (onClosePreview != null) {
                                // We are already inside the route-preview activity. Update this
                                // screen's destination instead of stacking another preview activity.
                                routeDestination = selectedPointForSheet
                                routeDestinationName = destinationName
                                selectedPoi = poiForSheet
                                calculatedRoutes = emptyList()
                                selectedRouteIndex = 0
                                activeRoute = null
                                isNavigating = false
                                tripPanelExpanded = false
                                guidance = null
                                offRouteFixCount = 0
                                lastRerouteAtMs = 0
                                routeOptionsExpanded = false
                                controller.updateRoutes(emptyList())
                            } else {
                                val intent = android.content.Intent(context, NavigationRoutePreviewActivity::class.java)
                                    .putExtra(NavigationRoutePreviewActivity.EXTRA_LATITUDE, selectedPointForSheet.latitude)
                                    .putExtra(NavigationRoutePreviewActivity.EXTRA_LONGITUDE, selectedPointForSheet.longitude)
                                    .putExtra(NavigationRoutePreviewActivity.EXTRA_DESTINATION_NAME, destinationName)
                                context.startActivity(intent)
                            }
                            showMapPointSheet = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Navigation, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Маршрут сюда")
                    }
                    TextButton(
                        onClick = { showMapPointSheet = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Закрыть")
                    }
                }
            }
        }

        if (showLocationModePicker) {
            ModalBottomSheet(
                onDismissRequest = { showLocationModePicker = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = colors.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Источник местоположения",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        "Карта использует готовую позицию из расчётов ClearGPS. Здесь можно переключать тот же режим, что и в основном приложении.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )

                    NavigationPositionMode.values().forEach { mode ->
                        val selected = selectedPositionMode == mode
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = if (selected) colors.secondaryContainer else colors.surfaceContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectPositionMode(mode) }
                                    .padding(horizontal = 14.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    modifier = Modifier.size(42.dp),
                                    shape = RoundedCornerShape(13.dp),
                                    color = if (selected) colors.primary else colors.surfaceContainerHigh
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            when (mode) {
                                                NavigationPositionMode.AUTO -> Icons.Outlined.Navigation
                                                NavigationPositionMode.GPS_ONLY -> Icons.Outlined.MyLocation
                                                NavigationPositionMode.RADIO_ONLY -> Icons.Outlined.Map
                                            },
                                            contentDescription = null,
                                            tint = if (selected) colors.onPrimary else colors.onSurface
                                        )
                                    }
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 13.dp, end = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        mode.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (selected) colors.onSecondaryContainer else colors.onSurface
                                    )
                                    Text(
                                        mode.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                                    )
                                }
                                RadioButton(
                                    selected = selected,
                                    onClick = { selectPositionMode(mode) }
                                )
                            }
                        }
                    }

                    val currentPositionDescription = navigationPosition?.let { position ->
                        val sourceTitle = when (position.source.uppercase()) {
                            "GPS" -> "GPS после фильтра ClearGPS"
                            "GPS_UNVERIFIED" -> "GPS-позиция · ещё не проверена фильтром"
                            "GPS_UNVERIFIED_STALE" -> "Последняя GPS-позиция · давно не обновлялась, не проверена фильтром"
                            "LAST_TRUSTED" -> "Последняя доверенная GPS-позиция"
                            "NETWORK" -> "Сеть Android"
                            "LOCAL_RADIO" -> "Радиорасчёт ClearGPS"
                            "LOCAL_RADIO_STALE" -> "Последняя радиопозиция ClearGPS"
                            "OBD2_DEAD_RECKONING" -> "GPS + прогноз по OBD-II"
                            "BEACONDB" -> "Радиооценка BeaconDB"
                            "MANUAL_ANCHOR" -> "Ручной ориентир ClearGPS"
                            else -> position.source
                        }
                        sourceTitle + (position.accuracyMeters?.let { " · ±${it.toInt()} м" } ?: "")
                    } ?: if (
                        positionPreferences.getBoolean(LocationMonitorService.KEY_MONITORING, false)
                    ) {
                        "ClearGPS ожидает надёжную координату"
                    } else {
                        "Мониторинг ClearGPS выключен"
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surfaceContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                "Текущая позиция",
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurfaceVariant
                            )
                            Text(
                                currentPositionDescription,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                if (controller.centerOnCurrentPosition()) {
                                    showLocationModePicker = false
                                } else {
                                    Toast.makeText(
                                        context,
                                        "ClearGPS пока не выдал координату для выбранного режима.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            },
                            enabled = navigationPosition != null
                        ) {
                            Text("Показать на карте")
                        }
                        TextButton(
                            onClick = {
                                showLocationModePicker = false
                                onOpenLocationSettings()
                            }
                        ) {
                            Text("Настройки ClearGPS")
                        }
                    }
                }
            }
        }

        if (showSettings) {
            NavigationSettingsScreen(
                onBack = {
                    showSettings = false
                    selectedNavigationTab = "Карта"
                },
                speechEnabled = speechEnabled,
                onSpeechEnabledChange = { enabled ->
                    speechEnabled = enabled
                    positionPreferences.edit().putBoolean(KEY_NAV_VOICE_ENABLED, enabled).apply()
                    if (!enabled) speechEngine?.stop()
                },
                onOpenLocationSettings = {
                    showSettings = false
                    selectedNavigationTab = "Карта"
                    onOpenLocationSettings()
                }
            )
        }

        AnimatedVisibility(
            visible = showSearch,
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 360),
                initialOffsetY = { fullHeight -> fullHeight }
            ) + fadeIn(animationSpec = tween(durationMillis = 220)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 280),
                targetOffsetY = { fullHeight -> fullHeight }
            ) + fadeOut(animationSpec = tween(durationMillis = 180))
        ) {
            NavigationSearchScreen(
                query = destinationQuery,
                onQueryChange = { query -> destinationQuery = query.take(160) },
                onBack = {
                    showSearch = false
                    selectedNavigationTab = "Карта"
                },
                onSelectCategory = { category ->
                    destinationQuery = category
                    showSearch = false
                    selectedNavigationTab = "Карта"
                    Toast.makeText(context, "Категория: $category", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

@Composable
private fun NavigationSettingsScreen(
    speechEnabled: Boolean,
    onSpeechEnabledChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenLocationSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var showRegionDownloads by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    if (showRegionDownloads) {
        OverpassRegionDownloadsScreen(onBack = { showRegionDownloads = false })
        return
    }
    if (showDiagnostics) {
        NavigationDiagnosticsScreen(onBack = { showDiagnostics = false })
        return
    }
    if (showAbout) {
        NavigationAboutScreen(
            onBack = { showAbout = false },
            onOpenDiagnostics = {
                showAbout = false
                showDiagnostics = true
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = colors.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 8.dp,
                        end = 18.dp,
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 6.dp,
                        bottom = 12.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
                }
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        "Настройки",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface
                    )
                    Text(
                        "ClearGPS Navigation",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 22.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Всё для удобной поездки",
                    style = MaterialTheme.typography.headlineSmall,
                    color = colors.onBackground
                )
                Text(
                    "Управляйте картой, позиционированием и параметрами навигации.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }

            NavigationSettingsGroup("ПОЕЗДКА") {
                NavigationSettingsItem(
                    title = "Местоположение и GPS",
                    subtitle = "Режимы GPS, Auto, Radio и диагностика",
                    icon = Icons.Outlined.MyLocation,
                    onClick = onOpenLocationSettings
                )
            }

            NavigationSettingsGroup("КАРТА И ОТОБРАЖЕНИЕ") {
                NavigationSettingsItem(
                    title = "Вид карты",
                    subtitle = "Тёмная навигационная карта на данных OpenFreeMap",
                    icon = Icons.Outlined.Map
                )
                NavigationSettingsDivider()
                NavigationSettingsItem(
                    title = "Источник карты",
                    subtitle = "OpenFreeMap · картографические данные OpenStreetMap",
                    icon = Icons.Outlined.Map
                )
                NavigationSettingsDivider()
                NavigationSettingsItem(
                    title = "Загрузка карт",
                    subtitle = "Дорожные данные регионов для офлайн-навигации",
                    icon = Icons.Outlined.Map,
                    onClick = { showRegionDownloads = true }
                )
            }

            NavigationSettingsGroup("НАВИГАЦИЯ") {
                NavigationSettingsItem(
                    title = "Поиск и маршруты",
                    subtitle = "Маршрут по точке на карте, до трёх вариантов и пошаговое ведение",
                    icon = Icons.Outlined.Navigation
                )
                NavigationSettingsDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.VolumeUp, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Google Text-to-Speech", style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                        Text(
                            if (speechEnabled) "Движок включён · озвучивание манёвров активно"
                            else "Движок выключен · только визуальные подсказки",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    Switch(checked = speechEnabled, onCheckedChange = onSpeechEnabledChange)
                }
            }

            NavigationSettingsGroup("О ПРИЛОЖЕНИИ") {
                NavigationSettingsItem(
                    title = "О ClearGPS",
                    subtitle = "Возможности, приватность, версия и информация о проекте",
                    icon = Icons.Outlined.Info,
                    onClick = { showAbout = true }
                )
                NavigationSettingsDivider()
                NavigationSettingsItem(
                    title = "Диагностика навигатора",
                    subtitle = "Состояние журналов и техническая информация",
                    icon = Icons.Outlined.Sync,
                    onClick = { showDiagnostics = true }
                )
            }
        }
    }
}


@Composable
private fun NavigationAboutScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "не определена"
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Surface(modifier = Modifier.fillMaxWidth(), color = colors.surfaceContainerHigh, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(
                    start = 8.dp, end = 18.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 6.dp,
                    bottom = 12.dp
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад") }
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("О приложении", style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                    Text("ClearGPS · информация о проекте", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 20.dp, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(Brush.linearGradient(listOf(colors.primary, colors.tertiary)), RoundedCornerShape(28.dp))
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(shape = RoundedCornerShape(18.dp), color = colors.onPrimary.copy(alpha = 0.14f)) {
                    Icon(Icons.Outlined.Navigation, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.padding(13.dp).size(30.dp))
                }
                Text("ClearGPS", style = MaterialTheme.typography.headlineLarge, color = colors.onPrimary)
                Text("Местоположение — под вашим контролем.", style = MaterialTheme.typography.titleMedium, color = colors.onPrimary)
                Text(
                    "Экспериментальный Android-проект, который объединяет навигацию, спутниковое позиционирование и анализ радиосреды.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onPrimary.copy(alpha = 0.88f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(shape = RoundedCornerShape(50), color = colors.onPrimary.copy(alpha = 0.16f)) {
                        Text("ANDROID", modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
                    }
                    Surface(shape = RoundedCornerShape(50), color = colors.onPrimary.copy(alpha = 0.16f)) {
                        Text("OPEN SOURCE", modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
                    }
                }
            }

            AboutInfoCard(
                title = "Что умеет ClearGPS",
                subtitle = "Инструменты для навигации и изучения качества позиционирования."
            ) {
                AboutFeatureRow(Icons.Outlined.MyLocation, "Несколько источников позиции", "GPS, Auto и Radio. В радиорежиме оценка может использовать локальную базу Wi-Fi, сотовых сетей и BLE-маяков.")
                NavigationSettingsDivider()
                AboutFeatureRow(Icons.Outlined.Map, "Карта и маршруты", "Карта на базе MapLibre, варианты маршрута, визуальные и голосовые подсказки.")
                NavigationSettingsDivider()
                AboutFeatureRow(Icons.Outlined.Info, "Диагностика", "Информация об источнике координат, состоянии мониторинга и работе позиционирования.")
            }

            AboutInfoCard(
                title = "Приватность и контроль",
                subtitle = "Радионаблюдения хранятся в закрытой базе приложения на устройстве. Обращение к внешним источникам позиционирования зависит от отдельных разрешений и переключателей согласия."
            ) {
                Text(
                    "ClearGPS не отправляет записи радионаблюдений в базы сбора данных. При включении сетевых оценок соответствующие провайдеры получают запросы с устройства; карта и построение маршрутов также используют сетевые сервисы.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant
                )
            }

            AboutInfoCard(
                title = "Статус проекта",
                subtitle = "ClearGPS активно развивается и остаётся экспериментальным приложением."
            ) {
                Text(
                    "Загрузка дорожных данных регионов уже предусмотрена, но полноценное построение маршрутов без интернета ещё находится в разработке. Текущая карта и онлайн-маршрутизация требуют подключения к сети.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant
                )
            }

            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerHigh) {
                Column(modifier = Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Версия приложения", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                    Text(versionName, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                    Text("Android · ClearGPS Navigation", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }

            Button(
                onClick = {
                    runCatching {
                        context.startActivity(android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/MAKAKAIglitch/clear-gps")
                        ))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Открыть проект на GitHub") }
            TextButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть диагностику")
            }
            Text(
                "Картографические данные © OpenStreetMap contributors. Проект не является сертифицированным средством навигации или безопасности.",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AboutInfoCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun AboutFeatureRow(icon: ImageVector, title: String, description: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NavigationDiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var enabled by remember { mutableStateOf(DiagnosticLogStore.isEnabled(context)) }
    var maxKb by remember { mutableStateOf(DiagnosticLogStore.maxKb(context)) }
    var logText by remember { mutableStateOf(DiagnosticLogStore.read(context)) }
    var logSize by remember { mutableStateOf(DiagnosticLogStore.sizeBytes(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            enabled = DiagnosticLogStore.isEnabled(context)
            maxKb = DiagnosticLogStore.maxKb(context)
            logText = DiagnosticLogStore.read(context)
            logSize = DiagnosticLogStore.sizeBytes(context)
            delay(1_500L)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Surface(modifier = Modifier.fillMaxWidth(), color = colors.surfaceContainerHigh, tonalElevation = 2.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад") }
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("Диагностика навигатора", style = MaterialTheme.typography.titleLarge)
                    Text("ClearGPS Navigation · подробный журнал", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Подробное логирование", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (enabled) "Запись событий навигации включена" else "Запись выключена",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = {
                            DiagnosticLogStore.setEnabled(context, it)
                            enabled = it
                            logText = DiagnosticLogStore.read(context)
                            logSize = DiagnosticLogStore.sizeBytes(context)
                        })
                    }
                    Text(
                        "Записываются обновления позиции (без точных координат), точность, запросы и результаты маршрутов, ошибки, перестроения и голосовые манёвры. Журнал хранится в приватной папке приложения.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Размер журнала", style = MaterialTheme.typography.titleSmall)
                    Text("$maxKb КБ · занято ${logSize / 1024} КБ", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    Slider(
                        value = maxKb.toFloat(),
                        onValueChange = { value ->
                            maxKb = ((value / 64f).toInt() * 64).coerceIn(
                                DiagnosticLogStore.MIN_MAX_KB,
                                DiagnosticLogStore.MAX_MAX_KB
                            )
                        },
                        onValueChangeFinished = {
                            DiagnosticLogStore.setMaxKb(context, maxKb)
                            logText = DiagnosticLogStore.read(context)
                            logSize = DiagnosticLogStore.sizeBytes(context)
                        },
                        valueRange = DiagnosticLogStore.MIN_MAX_KB.toFloat()..DiagnosticLogStore.MAX_MAX_KB.toFloat(),
                        steps = 6
                    )
                    Text(
                        "Лимит: от ${DiagnosticLogStore.MIN_MAX_KB} до ${DiagnosticLogStore.MAX_MAX_KB} КБ. Старые записи удаляются при переполнении.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "ClearGPS Navigation diagnostics",
                                DiagnosticLogStore.read(context)
                            )
                        )
                        Toast.makeText(context, "Журнал скопирован", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Копировать логи") }
                TextButton(
                    onClick = {
                        DiagnosticLogStore.clear(context)
                        logText = DiagnosticLogStore.read(context)
                        logSize = DiagnosticLogStore.sizeBytes(context)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Очистить") }
            }

            Text("Журнал событий", style = MaterialTheme.typography.titleMedium)
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 520.dp),
                shape = RoundedCornerShape(12.dp),
                color = colors.surfaceContainer
            ) {
                SelectionContainer {
                    Text(
                        logText,
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurface
                    )
                }
            }
            Text(
                "Чтобы собрать отчёт: включи запись, повтори проблему и нажми «Копировать логи». Затем пришли скопированный текст сюда.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}


@Composable
private fun OverpassRegionDownloadsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var countryQuery by remember { mutableStateOf("") }
    var regionQuery by remember { mutableStateOf("") }
    var countries by remember { mutableStateOf<List<OverpassRegionChoice>>(emptyList()) }
    var selectedCountry by remember { mutableStateOf<OverpassRegionChoice?>(null) }
    var results by remember { mutableStateOf<List<OverpassRegionChoice>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var countriesLoading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    var workInfos by remember { mutableStateOf<List<androidx.work.WorkInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (true) {
            revision++
            workInfos = withContext(Dispatchers.IO) { OverpassRoadRegionQueue.workInfos(context) }
            delay(1_000L)
        }
    }
    LaunchedEffect(Unit) {
        countries = withContext(Dispatchers.IO) { OverpassRegionSearch.cachedCountries(context) }
        countriesLoading = countries.isEmpty()
        while (countriesLoading) {
            delay(1_500L)
            val cached = withContext(Dispatchers.IO) { OverpassRegionSearch.cachedCountries(context) }
            if (cached.isNotEmpty()) {
                countries = cached
                countriesLoading = false
                message = null
            }
        }
    }
    LaunchedEffect(selectedCountry?.relationId) {
        val country = selectedCountry ?: return@LaunchedEffect
        searching = true
        regionQuery = ""
        results = emptyList()
        message = null
        results = withContext(Dispatchers.IO) {
            OverpassRegionSearch.cachedRegions(context, country.relationId)
        }
        if (results.isNotEmpty() || withContext(Dispatchers.IO) {
                OverpassRegionSearch.hasCachedRegions(context, country.relationId)
            }) {
            searching = false
            if (results.isEmpty()) message = "Для этой страны не найдены административные регионы в OpenStreetMap."
        } else {
            OverpassRoadRegionQueue.enqueueRegionCatalog(context, country.relationId)
            while (searching) {
                delay(1_500L)
                val cached = withContext(Dispatchers.IO) {
                    OverpassRegionSearch.cachedRegions(context, country.relationId)
                }
                val complete = withContext(Dispatchers.IO) {
                    OverpassRegionSearch.hasCachedRegions(context, country.relationId)
                }
                if (complete) {
                    results = cached
                    searching = false
                    if (cached.isEmpty()) message = "Для этой страны не найдены административные регионы в OpenStreetMap."
                }
            }
        }
    }

    val prefs = OverpassRoadRegionQueue.prefs(context)
    val currentId = remember(revision) { OverpassRoadRegionQueue.currentId(context) }
    val downloaded = remember(revision) { OverpassRoadRegionQueue.ids(context).sorted() }
    val currentName = remember(revision) { prefs.getString("current_name", null) }
    val activeWork = workInfos.firstOrNull {
        it.state == androidx.work.WorkInfo.State.RUNNING || it.state == androidx.work.WorkInfo.State.ENQUEUED
    }
    val activeProgress = activeWork?.progress?.getInt("progress", 0) ?: 0
    val activeName = activeWork?.progress?.getString("region_name") ?: "Загрузка региона"
    val activePhase = activeWork?.progress?.getString("phase")
        ?: if (activeWork?.state == androidx.work.WorkInfo.State.ENQUEUED) "В очереди" else "Подготовка загрузки"
    val visibleCountries = countries.filter { it.name.contains(countryQuery.trim(), ignoreCase = true) }
    val visibleRegions = results.filter { it.name.contains(regionQuery.trim(), ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Surface(modifier = Modifier.fillMaxWidth(), color = colors.surface, tonalElevation = 1.dp) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (selectedCountry != null) {
                        selectedCountry = null
                        regionQuery = ""
                        message = null
                    } else onBack()
                }) { Icon(Icons.Outlined.ArrowBack, contentDescription = if (selectedCountry != null) "К списку стран" else "Назад") }
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Офлайн-карты", style = MaterialTheme.typography.headlineSmall)
                    Text(selectedCountry?.name ?: "Выбор страны · весь мир",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Surface(shape = CircleShape, color = colors.secondaryContainer) {
                    Text(downloaded.size.toString(), modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge, color = colors.onSecondaryContainer)
                }
            }
        }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.primaryContainer) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ваши карты", style = MaterialTheme.typography.titleLarge, color = colors.onPrimaryContainer)
                    Text("Выбери страну, затем нужную область или административный регион. Сохраняй дорожные данные для подготовки к будущей офлайн-навигации.",
                        style = MaterialTheme.typography.bodyMedium, color = colors.onPrimaryContainer)
                    Text("Сейчас загружаются дорожные данные OpenStreetMap, а не сами тайлы подложки.",
                        style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer)
                }
            }
            if (activeWork != null) {
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerHigh) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(activeName, style = MaterialTheme.typography.titleSmall)
                                Text(activePhase, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                            if (activeProgress > 0) Text(activeProgress.toString() + "%", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                        }
                        if (activeProgress > 0) {
                            androidx.compose.material3.LinearProgressIndicator(progress = { activeProgress.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                        } else {
                            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Сохранённые регионы", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(downloaded.size.toString() + " регионов", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                if (downloaded.isEmpty()) {
                    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer) {
                        Column(modifier = Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Icon(Icons.Outlined.Map, contentDescription = null, tint = colors.secondary, modifier = Modifier.size(34.dp))
                            Text("Пока ничего не скачано", style = MaterialTheme.typography.titleSmall)
                            Text(currentName?.let { "Текущий регион: " + it + " · ожидает загрузки" } ?: "Выбери страну и регион ниже.",
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                } else {
                    downloaded.forEach { id ->
                        var menuExpanded by remember(id) { mutableStateOf(false) }
                        val regionName = OverpassRoadRegionQueue.name(context, id)
                        val stamp = OverpassRoadRegionQueue.updated(context, id)
                        val date = if (stamp > 0L) java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
                            .format(java.util.Date(stamp)) else "дата неизвестна"
                        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerHigh) {
                            Row(modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(12.dp), color = colors.secondaryContainer) {
                                    Icon(Icons.Outlined.Map, contentDescription = null, tint = colors.onSecondaryContainer,
                                        modifier = Modifier.padding(10.dp).size(22.dp))
                                }
                                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(regionName, style = MaterialTheme.typography.titleSmall)
                                    Text(if (id == currentId) "Текущий регион · " + date else "Сохранено · " + date,
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                                Box {
                                    IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Действия для " + regionName) }
                                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                        DropdownMenuItem(text = { Text("Обновить") }, onClick = {
                                            menuExpanded = false
                                            OverpassRoadRegionQueue.enqueueRegion(context, id, regionName, force = true)
                                            message = "Обновление «" + regionName + "» добавлено в очередь."
                                        }, leadingIcon = { Icon(Icons.Outlined.Sync, contentDescription = null) })
                                        DropdownMenuItem(text = { Text("Удалить") }, onClick = {
                                            menuExpanded = false
                                            OverpassRoadRegionQueue.deleteRegion(context, id)
                                            revision++
                                            message = "«" + regionName + "» удалена из сохранённых регионов."
                                        }, leadingIcon = { Icon(Icons.Outlined.Close, contentDescription = null) })
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (selectedCountry == null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Страны", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(if (countriesLoading) "Загрузка…" else countries.size.toString(),
                            style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                    OutlinedTextField(value = countryQuery, onValueChange = { countryQuery = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Поиск страны") },
                        placeholder = { Text("Например, Финляндия") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                    if (countriesLoading) {
                        androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else if (visibleCountries.isEmpty()) {
                        Text("Страны не найдены.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } else {
                        visibleCountries.forEach { country ->
                            Surface(modifier = Modifier.fillMaxWidth().clickable { selectedCountry = country },
                                shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh) {
                                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(country.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                                    Icon(Icons.Outlined.ChevronRight, contentDescription = "Выбрать " + country.name, tint = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Регионы · " + selectedCountry!!.name, style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = regionQuery, onValueChange = { regionQuery = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Поиск региона") },
                        placeholder = { Text("Название области или региона") }, singleLine = true, shape = RoundedCornerShape(16.dp))
                    if (searching) {
                        androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Загружаем регионы страны…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } else if (visibleRegions.isEmpty()) {
                        Text("Регионы не найдены.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    } else {
                        visibleRegions.forEach { region ->
                            val isDownloaded = region.relationId in downloaded
                            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = colors.surfaceContainerHigh) {
                                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(region.name, style = MaterialTheme.typography.titleSmall)
                                        Text(if (isDownloaded) "Уже в сохранённых" else "Дорожные данные · OpenStreetMap",
                                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    }
                                    if (isDownloaded) Text("Скачано", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                                    else Button(onClick = {
                                        OverpassRoadRegionQueue.enqueueRegion(context, region.relationId,
                                            selectedCountry!!.name + " · " + region.name)
                                        message = "Регион «" + region.name + "» добавлен в очередь загрузки."
                                    }, shape = RoundedCornerShape(14.dp)) { Text("Скачать") }
                                }
                            }
                        }
                    }
                }
            }
            message?.let {
                Surface(shape = RoundedCornerShape(14.dp), color = colors.secondaryContainer) {
                    Text(it, modifier = Modifier.fillMaxWidth().padding(13.dp), style = MaterialTheme.typography.bodySmall,
                        color = colors.onSecondaryContainer)
                }
            }
            Text("Важно: текущая версия сохраняет сжатые дорожные данные выбранного региона. Файлы подложки OpenFreeMap не загружаются, а построение офлайн-графа и полноценная офлайн-навигация ещё не реализованы. © OpenStreetMap contributors, ODbL.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun NavigationSettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 1.dp
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun NavigationSettingsItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(clickModifier)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(14.dp),
            color = colors.secondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.onSecondaryContainer)
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        if (onClick != null) {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = "Открыть",
                tint = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NavigationSettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 74.dp, end = 16.dp)
            .size(width = 1.dp, height = 1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    )
}


private data class NavigationStatusNotice(
    val key: String,
    val title: String,
    val kind: Kind,
    val priority: Int,
    val updatedAt: Long
) {
    enum class Kind { NO_INTERNET, LOCATION, WARNING, ROUTE }
}

@Composable
private fun rememberValidatedInternet(context: android.content.Context): Boolean {
    var connected by remember {
        mutableStateOf(validatedInternetAvailable(
            context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        ))
    }
    DisposableEffect(context) {
        val manager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connected = validatedInternetAvailable(manager)
            }
            override fun onLost(network: Network) {
                connected = validatedInternetAvailable(manager)
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                connected = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        var registered = false
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: RuntimeException) { }
        onDispose {
            if (registered) {
                try { manager.unregisterNetworkCallback(callback) } catch (_: RuntimeException) { }
            }
        }
    }
    return connected
}

private fun validatedInternetAvailable(manager: ConnectivityManager): Boolean {
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

@Composable
private fun NavigationStatusStrip(
    hasInternet: Boolean,
    position: NavigationPositionSnapshot?,
    positionMode: NavigationPositionMode,
    routeLoading: Boolean,
    routeError: String?
) {
    var notices by remember { mutableStateOf(emptyList<NavigationStatusNotice>()) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(hasInternet, position, positionMode, routeLoading, routeError) {
        val desired = buildList {
            if (!hasInternet) add(NavigationStatusNotice("internet", "Нет интернета", NavigationStatusNotice.Kind.NO_INTERNET, 100, 0L))
            if (!routeError.isNullOrBlank()) add(NavigationStatusNotice("route-error", "Ошибка маршрута", NavigationStatusNotice.Kind.ROUTE, 95, 0L))
            if (routeLoading) add(NavigationStatusNotice("route-loading", "Перестраиваю маршрут", NavigationStatusNotice.Kind.ROUTE, 85, 0L))
            if (position == null) {
                add(NavigationStatusNotice(
                    "position-missing",
                    if (positionMode == NavigationPositionMode.RADIO_ONLY) "Нет радиопозиции" else "Нет геопозиции",
                    NavigationStatusNotice.Kind.WARNING, 80, 0L
                ))
            } else {
                val source = position.source.uppercase()
                if (source == "NETWORK_UNVERIFIED") {
                    add(NavigationStatusNotice("position-unverified", "Сеть Android · примерное место", NavigationStatusNotice.Kind.WARNING, 75, 0L))
                } else if (source.contains("UNVERIFIED")) {
                    add(NavigationStatusNotice("position-unverified", "GPS не проверен", NavigationStatusNotice.Kind.WARNING, 75, 0L))
                }
                if (position.accuracyMeters != null && position.accuracyMeters > 50.0) {
                    val accuracyLabel = if (position.accuracyMeters >= 1_000.0) {
                        "±%.1f км".format(java.util.Locale.US, position.accuracyMeters / 1_000.0)
                    } else {
                        "±${position.accuracyMeters.toInt()} м"
                    }
                    add(NavigationStatusNotice("position-accuracy", "Точность $accuracyLabel", NavigationStatusNotice.Kind.WARNING, 65, 0L))
                }
                if (source.contains("RADIO") || source == "BEACONDB") {
                    add(NavigationStatusNotice("radio-position", "Радиопозиция", NavigationStatusNotice.Kind.LOCATION, 45, 0L))
                }
            }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = notices.associateBy { it.key }
        notices = desired.map { item ->
            val old = previous[item.key]
            item.copy(updatedAt = if (old == null || old.title != item.title) now else old.updatedAt)
        }.sortedWith(compareByDescending<NavigationStatusNotice> { it.updatedAt }.thenByDescending { it.priority })
        if (notices.isEmpty()) expanded = false
    }

    if (notices.isEmpty()) return
    val newest = notices.first()
    val older = notices.drop(1)
    val colors = MaterialTheme.colorScheme

    @Composable
    fun NoticeIcon(notice: NavigationStatusNotice, compact: Boolean) {
        val icon = when (notice.kind) {
            NavigationStatusNotice.Kind.NO_INTERNET -> Icons.Outlined.WifiOff
            NavigationStatusNotice.Kind.LOCATION -> Icons.Outlined.MyLocation
            NavigationStatusNotice.Kind.WARNING -> Icons.Outlined.WarningAmber
            NavigationStatusNotice.Kind.ROUTE -> Icons.Outlined.Sync
        }
        val tint = when (notice.kind) {
            NavigationStatusNotice.Kind.NO_INTERNET, NavigationStatusNotice.Kind.ROUTE -> Color(0xFF8C1D18)
            NavigationStatusNotice.Kind.WARNING -> Color(0xFF765000)
            NavigationStatusNotice.Kind.LOCATION -> colors.onSecondaryContainer
        }
        Icon(icon, contentDescription = notice.title, modifier = Modifier.size(if (compact) 15.dp else 16.dp), tint = tint)
    }

    @Composable
    fun NoticePill(notice: NavigationStatusNotice, compact: Boolean) {
        val background = when (notice.kind) {
            NavigationStatusNotice.Kind.NO_INTERNET, NavigationStatusNotice.Kind.ROUTE -> Color(0xFFFFE7E5)
            NavigationStatusNotice.Kind.WARNING -> Color(0xFFFFF1CC)
            NavigationStatusNotice.Kind.LOCATION -> colors.secondaryContainer
        }
        val foreground = when (notice.kind) {
            NavigationStatusNotice.Kind.NO_INTERNET, NavigationStatusNotice.Kind.ROUTE -> Color(0xFF8C1D18)
            NavigationStatusNotice.Kind.WARNING -> Color(0xFF765000)
            NavigationStatusNotice.Kind.LOCATION -> colors.onSecondaryContainer
        }
        Surface(
            modifier = Modifier.then(if (compact) Modifier.size(30.dp) else Modifier.widthIn(max = 190.dp)).clickable { expanded = !expanded },
            shape = CircleShape,
            color = background
        ) {
            Row(
                modifier = Modifier.padding(horizontal = if (compact) 0.dp else 11.dp, vertical = if (compact) 0.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                NoticeIcon(notice, compact)
                if (!compact) {
                    Text(notice.title, modifier = Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.labelMedium, color = foreground, maxLines = 1)
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            NoticePill(newest, compact = false)
            older.take(2).forEach { NoticePill(it, compact = true) }
            if (older.size > 2) {
                Surface(
                    modifier = Modifier.size(30.dp).clickable { expanded = !expanded },
                    shape = CircleShape,
                    color = colors.surfaceVariant
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("+${older.size - 2}", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        if (expanded && older.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                older.forEach { NoticePill(it, compact = false) }
            }
        }
    }
}

@Composable
private fun BoxScope.NavigationTripPanel(
    route: NavigationRouteAlternative?,
    progress: NavigationGuidanceSnapshot?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onFinish: () -> Unit
) {
    val remainingMeters = progress?.remainingDistanceMeters
        ?: route?.distanceMeters
        ?: 0.0
    val totalMeters = route?.distanceMeters?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    val traveledMeters = (totalMeters - remainingMeters).coerceIn(0.0, totalMeters)
    val progressFraction = if (totalMeters > 0.0) {
        (traveledMeters / totalMeters).toFloat().coerceIn(0f, 1f)
    } else 0f
    val remainingSeconds = progress?.remainingDurationSeconds
        ?: route?.durationSeconds
        ?: 0.0
    val arrivalTime = java.time.LocalTime.now()
        .plusSeconds(remainingSeconds.toLong().coerceAtLeast(0L))
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    val panelColor = Color(0xFF152232)
    val primaryText = Color(0xFFF4F8FC)
    val secondaryText = Color(0xFFB8C9D8)
    val trackColor = Color(0xFF526779)
    val progressColor = Color(0xFF55B6FF)
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = bottomInset + 10.dp)
            .shadow(12.dp, RoundedCornerShape(24.dp))
            .clickable { onExpandedChange(!expanded) },
        shape = RoundedCornerShape(24.dp),
        color = panelColor,
        tonalElevation = 5.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(260))
                .padding(horizontal = 16.dp, vertical = if (expanded) 15.dp else 11.dp),
            verticalArrangement = Arrangement.spacedBy(if (expanded) 13.dp else 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (expanded) {
                        Text("ПОЕЗДКА", style = MaterialTheme.typography.labelSmall, color = secondaryText)
                    }
                    Text(
                        "$arrivalTime · прибытие",
                        style = MaterialTheme.typography.titleMedium,
                        color = primaryText
                    )
                    if (!expanded) {
                        Text(
                            "${formatRouteDuration(remainingSeconds)} · ${formatRouteDistance(remainingMeters)} осталось",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryText
                        )
                    }
                }
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = if (expanded) "Свернуть панель поездки" else "Развернуть панель поездки",
                    tint = primaryText,
                    modifier = Modifier
                        .size(25.dp)
                        .graphicsLayer { rotationZ = if (expanded) -90f else 90f }
                )
            }

            if (expanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(arrivalTime, style = MaterialTheme.typography.headlineSmall, color = primaryText)
                        Text("Прибытие", style = MaterialTheme.typography.labelSmall, color = secondaryText)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(formatRouteDuration(remainingSeconds), style = MaterialTheme.typography.titleLarge, color = primaryText)
                        Text("Осталось", style = MaterialTheme.typography.labelSmall, color = secondaryText)
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(formatRouteDistance(remainingMeters), style = MaterialTheme.typography.titleLarge, color = primaryText)
                        Text("До цели", style = MaterialTheme.typography.labelSmall, color = secondaryText)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Проехано ${formatRouteDistance(traveledMeters)}", style = MaterialTheme.typography.labelSmall, color = secondaryText)
                        Text("Осталось ${formatRouteDistance(remainingMeters)}", style = MaterialTheme.typography.labelSmall, color = secondaryText)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 7.dp)
                            .background(trackColor, CircleShape)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressFraction)
                                .heightIn(min = 7.dp)
                                .background(progressColor, CircleShape)
                        )
                    }
                    Text(
                        "${(progressFraction * 100).toInt()}% маршрута пройдено",
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryText
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onFinish) {
                        Text("Завершить маршрут", color = Color(0xFFFFB4AB))
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 4.dp)
                        .background(trackColor, CircleShape)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .heightIn(min = 4.dp)
                            .background(progressColor, CircleShape)
                    )
                }
            }
        }
    }
}

private fun formatRouteDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).toInt().coerceAtLeast(1)
    val hours = minutes / 60
    return if (hours > 0) "${hours} ч ${minutes % 60} мин" else "${minutes} мин"
}

private fun formatRouteDistance(meters: Double): String =
    if (meters >= 1000.0) String.format(java.util.Locale.getDefault(), "%.1f км", meters / 1000.0)
    else "${meters.toInt()} м"


@Composable
private fun NavigationSpeedBadges(
    currentSpeedKmh: Int?,
    speedLimitKmh: Int?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SpeedBadge(value = currentSpeedKmh?.toString() ?: "—", isLimit = false)
        SpeedBadge(value = speedLimitKmh?.toString() ?: "—", isLimit = true)
    }
}

@Composable
private fun SpeedBadge(value: String, isLimit: Boolean) {
    Surface(
        modifier = Modifier
            .size(if (isLimit) 52.dp else 48.dp)
            .then(if (isLimit) Modifier.border(3.dp, Color(0xFFD32F2F), CircleShape) else Modifier),
        shape = CircleShape,
        color = Color.White,
        shadowElevation = 5.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(value, style = MaterialTheme.typography.titleMedium, color = Color(0xFF171717), maxLines = 1)
        }
    }
}
