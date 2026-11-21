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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.maplibre.android.maps.MapView

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
    var zoomLevel by remember { mutableStateOf(controller.currentZoom().toFloat()) }
    var centerOnNextPosition by remember { mutableStateOf(false) }
    var showLocationModePicker by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var selectedNavigationTab by rememberSaveable { mutableStateOf("Карта") }
    var destinationQuery by rememberSaveable { mutableStateOf("") }
    var pendingPositionMode by remember { mutableStateOf<NavigationPositionMode?>(null) }

    val watchedPositionKeys = remember {
        setOf(
            ClearGpsPreferences.KEY_POSITION_MODE,
            LocationMonitorService.KEY_MONITORING,
            LocationMonitorService.KEY_HAS_OUTPUT,
            LocationMonitorService.KEY_SOURCE,
            LocationMonitorService.KEY_LAT,
            LocationMonitorService.KEY_LON,
            LocationMonitorService.KEY_ACCURACY,
        LocationMonitorService.KEY_BEARING
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

    LaunchedEffect(navigationPosition) {
        controller.updatePosition(navigationPosition)
    }
    LaunchedEffect(navigationPosition, centerOnNextPosition) {
        if (centerOnNextPosition && navigationPosition != null) {
            controller.centerOnCurrentPosition()
            centerOnNextPosition = false
        }
    }
    DisposableEffect(controller) {
        controller.setOnZoomChangedListener { zoom ->
            zoomLevel = zoom.toFloat().coerceIn(2f, 20f)
        }
        onDispose { controller.setOnZoomChangedListener(null) }
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

    val positionModePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val requestedMode = pendingPositionMode
        pendingPositionMode = null
        if (requestedMode != null) {
            val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val anyGranted = fineGranted ||
                context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            val preciseRequired = requestedMode != NavigationPositionMode.AUTO
            val allowed = if (preciseRequired) fineGranted else anyGranted
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
                            "Для расчёта местоположения необходимо разрешение геолокации."
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    fun selectPositionMode(mode: NavigationPositionMode) {
        val fineGranted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val anyGranted = fineGranted ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val preciseRequired = mode != NavigationPositionMode.AUTO
        val needsPermission = !anyGranted || (preciseRequired && !fineGranted)
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

    fun stepZoom(delta: Float) {
        val nextZoom = (controller.currentZoom().toFloat() + delta).coerceIn(2f, 20f)
        zoomLevel = nextZoom
        controller.setZoom(nextZoom.toDouble())
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

            // Full-height zoom control: plus, a vertical slider, and minus.
            BoxWithConstraints(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 7.dp)
                    .fillMaxHeight()
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 142.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 205.dp
                    )
                    .width(54.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = colors.surfaceContainerHigh.copy(alpha = 0.97f),
                        tonalElevation = 3.dp,
                        shadowElevation = 5.dp
                    ) {
                        IconButton(onClick = { stepZoom(1f) }) {
                            Icon(Icons.Outlined.Add, contentDescription = "Приблизить карту")
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        color = colors.surfaceContainerHigh.copy(alpha = 0.92f),
                        tonalElevation = 2.dp,
                        shadowElevation = 4.dp
                    ) {
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val sliderLength = (maxHeight - 18.dp).coerceAtLeast(120.dp)
                            Slider(
                                value = zoomLevel,
                                onValueChange = { value ->
                                    zoomLevel = value
                                    controller.setZoom(value.toDouble())
                                },
                                valueRange = 2f..20f,
                                modifier = Modifier
                                    .rotate(270f)
                                    .requiredWidth(sliderLength)
                                    .height(44.dp)
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = colors.surfaceContainerHigh.copy(alpha = 0.97f),
                        tonalElevation = 3.dp,
                        shadowElevation = 5.dp
                    ) {
                        IconButton(onClick = { stepZoom(-1f) }) {
                            Icon(Icons.Outlined.Remove, contentDescription = "Отдалить карту")
                        }
                    }
                }
            }

            // The location button opens the live GPS / Radio / Auto source selector.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 78.dp,
                        end = 12.dp
                    ),
                shape = CircleShape,
                color = colors.surfaceContainerHigh.copy(alpha = 0.97f),
                tonalElevation = 3.dp,
                shadowElevation = 5.dp
            ) {
                IconButton(
                    onClick = { showLocationModePicker = true },
                    modifier = Modifier.size(50.dp)
                ) {
                    Icon(
                        Icons.Outlined.MyLocation,
                        contentDescription = "Источник местоположения: ${selectedPositionMode.title}",
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
                                "Маршрут не выбран",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onSurface
                            )
                            Text(
                                "Найдите место, чтобы начать поездку",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
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

        BackHandler(enabled = showSettings || showLocationModePicker) {
            if (showSettings) {
                showSettings = false
                selectedNavigationTab = "Карта"
            } else {
                showLocationModePicker = false
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

