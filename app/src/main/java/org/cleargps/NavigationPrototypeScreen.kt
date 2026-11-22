package org.cleargps

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.maps.MapView
import kotlinx.coroutines.delay

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
private fun NavigationPrototypeScreen(
    onMapViewCreated: (MapView) -> Unit,
    onMapViewDisposed: (MapView) -> Unit,
    onOpenLocationSettings: () -> Unit
) {
    val context = LocalContext.current
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
    var centerOnNextPosition by remember { mutableStateOf(false) }
    var selectedMapPoint by remember { mutableStateOf<NavigationCoordinate?>(null) }
    var selectedPoi by remember { mutableStateOf<NavigationPoi?>(null) }
    var enabledPoiCategories by remember { mutableStateOf(NavigationPoiCategory.values().toSet()) }
    var routeDestination by remember { mutableStateOf<NavigationCoordinate?>(null) }
    val routingRepository = remember { NavigationRoutingRepository() }
    var calculatedRoutes by remember { mutableStateOf<List<NavigationRouteAlternative>>(emptyList()) }
    var selectedRouteIndex by remember { mutableStateOf(0) }
    var routeLoading by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var showRouteOptions by rememberSaveable { mutableStateOf(false) }
    var showMapPointSheet by rememberSaveable { mutableStateOf(false) }
    var showLocationModePicker by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var selectedNavigationTab by rememberSaveable { mutableStateOf("Карта") }
    var destinationQuery by rememberSaveable { mutableStateOf("") }
    var pendingPositionMode by remember { mutableStateOf<NavigationPositionMode?>(null) }
    var pendingCenterAfterPermission by remember { mutableStateOf(false) }

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
            LocationMonitorService.KEY_LAST_OBSERVED_GPS_ELAPSED_NANOS
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
            delay(500L)
        }
    }

    DisposableEffect(controller) {
        controller.setOnMapTapListener { point ->
            selectedPoi = null
            selectedMapPoint = point
            showMapPointSheet = true
        }
        controller.setOnPoiTapListener { poi ->
            selectedPoi = poi
            selectedMapPoint = poi.coordinate
            showMapPointSheet = true
        }
        onDispose {
            controller.setOnMapTapListener(null)
            controller.setOnPoiTapListener(null)
        }
    }

    LaunchedEffect(navigationPosition) {
        controller.updatePosition(navigationPosition)
    }
    LaunchedEffect(enabledPoiCategories) {
        controller.updatePoiCategories(enabledPoiCategories)
    }
    LaunchedEffect(routeDestination) {
        controller.updateDestination(routeDestination)
    }

    LaunchedEffect(routeDestination, navigationPosition?.coordinate) {
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
                routingRepository.findRoutes(origin, destination) { result ->
                    routeLoading = false
                    result.onSuccess { alternatives ->
                        calculatedRoutes = alternatives.take(3)
                        selectedRouteIndex = 0
                        controller.updateRoutes(calculatedRoutes)
                        showRouteOptions = true
                    }.onFailure { error ->
                        routeError = error.message ?: "Не удалось построить маршрут"
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedNavigationTab = "Поиск"
                            showSearch = true
                        }
                        .padding(start = 15.dp, end = 7.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = "Поиск места",
                        tint = colors.primary
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp, top = 3.dp, bottom = 3.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Text(
                            text = if (destinationQuery.isBlank()) "Куда едем?" else destinationQuery,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurface
                        )
                        Text(
                            text = "Адрес или название места",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        selectedNavigationTab = "Настройки"
                        showSearch = false
                        showSettings = true
                    }) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Настройки навигации",
                            tint = colors.onSurface
                        )
                    }
                }
            }

            // OSM point-of-interest filters.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 82.dp),
                shape = RoundedCornerShape(20.dp),
                color = colors.surfaceContainerHigh.copy(alpha = 0.96f),
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NavigationPoiCategory.values().forEach { category ->
                        FilterChip(
                            selected = category in enabledPoiCategories,
                            onClick = {
                                enabledPoiCategories = if (category in enabledPoiCategories) {
                                    enabledPoiCategories - category
                                } else {
                                    enabledPoiCategories + category
                                }
                            },
                            label = { Text(category.title, style = MaterialTheme.typography.labelMedium) }
                        )
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
                        .fillMaxWidth()
                        .shadow(8.dp, RoundedCornerShape(26.dp)),
                    shape = RoundedCornerShape(26.dp),
                    color = colors.surfaceContainerHigh.copy(alpha = 0.98f),
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 17.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(15.dp),
                            color = colors.primaryContainer,
                            modifier = Modifier.size(46.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Outlined.Navigation,
                                    contentDescription = null,
                                    tint = colors.onPrimaryContainer
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                when {
                                    routeLoading -> "Строю варианты маршрута…"
                                    calculatedRoutes.isNotEmpty() -> "Самый быстрый маршрут"
                                    routeError != null -> "Не удалось построить маршрут"
                                    routeDestination != null -> "Пункт назначения выбран"
                                    else -> "Маршрут не выбран"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface
                            )
                            Text(
                                when {
                                    calculatedRoutes.isNotEmpty() -> {
                                        val route = calculatedRoutes[selectedRouteIndex.coerceIn(0, calculatedRoutes.lastIndex)]
                                        "${formatRouteDuration(route.durationSeconds)} · ${formatRouteDistance(route.distanceMeters)} · ${calculatedRoutes.size} вариантов"
                                    }
                                    routeLoading -> "Запрашиваю до трёх маршрутов"
                                    routeError != null -> routeError!!
                                    routeDestination != null -> "Ожидаю координаты отправления"
                                    else -> "Нажмите на карту, чтобы выбрать точку назначения"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        if (calculatedRoutes.isNotEmpty()) {
                            TextButton(onClick = { showRouteOptions = true }) {
                                Text("Маршруты")
                            }
                        }
                    }
                }

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
        }

        BackHandler(enabled = showSettings || showLocationModePicker || showMapPointSheet) {
            when {
                showMapPointSheet -> showMapPointSheet = false
                showSettings -> {
                    showSettings = false
                    selectedNavigationTab = "Карта"
                }
                else -> showLocationModePicker = false
            }
        }


        if (showRouteOptions && calculatedRoutes.isNotEmpty()) {
            ModalBottomSheet(
                onDismissRequest = { showRouteOptions = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = colors.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 30.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Выберите маршрут", style = MaterialTheme.typography.headlineSmall)
                    Text("Показаны до трёх вариантов. По умолчанию выбран самый быстрый.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    calculatedRoutes.forEachIndexed { index, route ->
                        val selected = index == selectedRouteIndex
                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable {
                                selectedRouteIndex = index
                                val ordered = listOf(route) + calculatedRoutes.filterIndexed { otherIndex, _ -> otherIndex != index }
                                controller.updateRoutes(ordered)
                                showRouteOptions = false
                            },
                            shape = RoundedCornerShape(18.dp),
                            color = if (selected) colors.secondaryContainer else colors.surfaceContainer
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = {
                                    selectedRouteIndex = index
                                    val ordered = listOf(route) + calculatedRoutes.filterIndexed { otherIndex, _ -> otherIndex != index }
                                    controller.updateRoutes(ordered)
                                    showRouteOptions = false
                                })
                                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                    Text(if (index == 0) "Самый быстрый" else "Вариант ${index + 1}", style = MaterialTheme.typography.titleMedium)
                                    Text("${formatRouteDuration(route.durationSeconds)} · ${formatRouteDistance(route.distanceMeters)}", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                }
                                if (selected) Text("Выбран", color = colors.primary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Button(onClick = { showRouteOptions = false }, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
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
                            poiForSheet.category.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary
                        )
                        poiForSheet.address?.let { address ->
                            Text(address, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
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
                            routeDestination = selectedPointForSheet
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
                onOpenLocationSettings = {
                    showSettings = false
                    selectedNavigationTab = "Карта"
                    onOpenLocationSettings()
                }
            )
        }

        if (showSearch) {
            ModalBottomSheet(
                onDismissRequest = {
                    showSearch = false
                    selectedNavigationTab = "Карта"
                },
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
                    Text("Куда едем?", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = destinationQuery,
                        onValueChange = { query -> destinationQuery = query.take(160) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        },
                        placeholder = { Text("Адрес или название места") }
                    )
                    Text(
                        "Поиск адресов и построение маршрутов подключим следующим этапом.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            showSearch = false
                            selectedNavigationTab = "Карта"
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Готово")
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationSettingsScreen(
    onBack: () -> Unit,
    onOpenLocationSettings: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

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
                    subtitle = "Светлая или тёмная тема по настройкам системы",
                    icon = Icons.Outlined.Map
                )
                NavigationSettingsDivider()
                NavigationSettingsItem(
                    title = "Источник карты",
                    subtitle = "OpenFreeMap · картографические данные OpenStreetMap",
                    icon = Icons.Outlined.Map
                )
            }

            NavigationSettingsGroup("НАВИГАЦИЯ") {
                NavigationSettingsItem(
                    title = "Поиск и маршруты",
                    subtitle = "Интерфейс поиска есть; построение маршрутов ещё не подключено",
                    icon = Icons.Outlined.Navigation
                )
                NavigationSettingsDivider()
                NavigationSettingsItem(
                    title = "Голосовые подсказки",
                    subtitle = "Станут доступны после подключения движка маршрутизации",
                    icon = Icons.Outlined.VolumeUp
                )
            }

            NavigationSettingsGroup("О ПРИЛОЖЕНИИ") {
                NavigationSettingsItem(
                    title = "ClearGPS Navigation",
                    subtitle = "Версия 0.4.0 · Навигационный интерфейс",
                    icon = Icons.Outlined.Info
                )
            }
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


private fun formatRouteDuration(seconds: Double): String {
    val minutes = (seconds / 60.0).toInt().coerceAtLeast(1)
    val hours = minutes / 60
    return if (hours > 0) "${hours} ч ${minutes % 60} мин" else "${minutes} мин"
}

private fun formatRouteDistance(meters: Double): String =
    if (meters >= 1000.0) String.format(java.util.Locale.getDefault(), "%.1f км", meters / 1000.0)
    else "${meters.toInt()} м"
