package org.cleargps

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateBottomPadding
import androidx.compose.foundation.layout.calculateTopPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
    val initialPosition = remember { readNavigationPosition(context) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var destinationQuery by rememberSaveable { mutableStateOf("") }

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
                            initialPosition = initialPosition
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
                        .clickable { showSearch = true }
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
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Настройки навигации",
                            tint = colors.onSurface
                        )
                    }
                }
            }

            // Compact zoom and location controls.
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = colors.surfaceContainerHigh.copy(alpha = 0.97f),
                    tonalElevation = 3.dp,
                    shadowElevation = 5.dp
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { controller.zoomBy(1.0) }) {
                            Icon(Icons.Outlined.Add, contentDescription = "Приблизить")
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 9.dp),
                            color = colors.outlineVariant.copy(alpha = 0.7f)
                        ) {
                            Spacer(Modifier.size(width = 25.dp, height = 1.dp))
                        }
                        IconButton(onClick = { controller.zoomBy(-1.0) }) {
                            Icon(Icons.Outlined.Remove, contentDescription = "Отдалить")
                        }
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = colors.surfaceContainerHigh.copy(alpha = 0.97f),
                    tonalElevation = 3.dp,
                    shadowElevation = 5.dp
                ) {
                    IconButton(
                        onClick = {
                            if (!controller.centerOnCurrentPosition()) {
                                Toast.makeText(
                                    context,
                                    "Пока нет доступной координаты. Откройте настройки местоположения.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier.size(50.dp)
                    ) {
                        Icon(
                            Icons.Outlined.MyLocation,
                            contentDescription = "Моё местоположение",
                            tint = colors.primary
                        )
                    }
                }
            }

            // Route/status island.
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
                    )
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
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Открыть настройки"
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 13.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 112.dp
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

        if (showSettings) {
            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = colors.surfaceContainerHigh
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp)
                        .padding(bottom = 26.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Настройки", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "ClearGPS Navigation",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showSettings = false }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Закрыть")
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showSettings = false
                                onOpenLocationSettings()
                            },
                        shape = RoundedCornerShape(18.dp),
                        color = colors.surfaceContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(44.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = colors.secondaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Outlined.MyLocation,
                                        contentDescription = null,
                                        tint = colors.onSecondaryContainer
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 13.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    "Настройки местоположения",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    "Открыть существующий интерфейс ClearGPS",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Text("Карта", style = MaterialTheme.typography.titleSmall)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = colors.surfaceContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(15.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Онлайн-карта", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "OpenFreeMap загружается по мере просмотра. Расширенный размер кэша и офлайн-пакеты будут добавлены следующим этапом.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                    Text(
                        "Поиск, построение маршрута и подсказки манёвров пока представлены интерфейсным прототипом.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }

        if (showSearch) {
            ModalBottomSheet(
                onDismissRequest = { showSearch = false },
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
                        onValueChange = { destinationQuery = it.take(160) },
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
                        onClick = { showSearch = false },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Готово")
                    }
                }
            }
        }
    }
}

private fun readNavigationPosition(context: Context): NavigationCoordinate? {
    val prefs = context.applicationContext.getSharedPreferences(
        ClearGpsPreferences.PREFS,
        Context.MODE_PRIVATE
    )
    if (!prefs.getBoolean(LocationMonitorService.KEY_HAS_OUTPUT, false) ||
        !prefs.getBoolean(LocationMonitorService.KEY_MOCK_OUTPUT_ACTIVE, false)
    ) return null

    val latitude = prefs.all[LocationMonitorService.KEY_LAT]?.toString()?.toDoubleOrNull()
        ?: return null
    val longitude = prefs.all[LocationMonitorService.KEY_LON]?.toString()?.toDoubleOrNull()
        ?: return null
    return NavigationCoordinate(latitude, longitude).takeIf { it.isValid() }
}
