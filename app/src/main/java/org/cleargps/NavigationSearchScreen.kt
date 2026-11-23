package org.cleargps

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

internal data class NavigationGeocodingResult(
    val name: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val kind: String
)

@Composable
internal fun NavigationSearchScreen(
    query: String,
    position: NavigationCoordinate?,
    nearbyCategory: String?,
    nearbyResults: List<NavigationPoi>,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSelectCategory: (String) -> Unit,
    onSelectResult: (NavigationGeocodingResult) -> Unit,
    onSelectPoi: (NavigationPoi) -> Unit
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(ClearGpsPreferences.PREFS, Context.MODE_PRIVATE)
    }
    val repository = remember(context) { NavigationGeocodingRepository(context) }
    val keyboard = LocalSoftwareKeyboardController.current
    var consent by remember { mutableStateOf(preferences.getBoolean(KEY_GEOCODING_CONSENT, false)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<NavigationGeocodingResult>>(emptyList()) }
    var history by remember { mutableStateOf(readPlaces(preferences, KEY_SEARCH_HISTORY)) }
    var favorites by remember { mutableStateOf(readPlaces(preferences, KEY_SEARCH_FAVORITES)) }
    val colors = MaterialTheme.colorScheme
    val categories = listOf(
        "Заправки", "Электрозарядки", "Кафе и рестораны", "Парковки",
        "Продукты", "Магазины", "Аптеки", "Банкоматы"
    )

    DisposableEffect(repository) {
        onDispose { repository.cancel() }
    }
    LaunchedEffect(query, consent, nearbyCategory) {
        repository.cancel()
        if (!consent || nearbyCategory != null || query.trim().length < 2) {
            results = emptyList()
            loading = false
            error = null
        } else {
            delay(350)
            loading = true
            error = null
            repository.search(query.trim(), position) { found, failure ->
                results = found
                error = failure
                loading = false
            }
        }
    }

    fun savePlace(result: NavigationGeocodingResult, key: String, limit: Int): List<String> {
        val encoded = encodePlace(result)
        val previous = preferences.getStringSet(key, emptySet()).orEmpty().toList()
        val updated = (listOf(encoded) + previous.filterNot { it == encoded }).take(limit)
        preferences.edit().putStringSet(key, updated.toSet()).apply()
        return updated
    }

    fun selectResult(result: NavigationGeocodingResult) {
        history = savePlace(result, KEY_SEARCH_HISTORY, 12)
        keyboard?.hide()
        onSelectResult(result)
    }

    Surface(Modifier.fillMaxSize(), color = colors.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { if (nearbyCategory != null) onSelectCategory("") else onBack() }) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
                }
                Column(Modifier.weight(1f)) {
                    Text(nearbyCategory ?: "Куда едем?", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        if (nearbyCategory != null) "Рядом с центром карты · 10 км"
                        else "Города, улицы, адреса и места",
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant
                    )
                }
            }
            if (nearbyCategory == null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Очистить")
                        }
                    },
                    placeholder = { Text("Город, улица, адрес или место") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() })
                )
            }
            Spacer(Modifier.height(10.dp))
            when {
                !consent && nearbyCategory == null -> {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Глобальный поиск", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Для поиска городов, улиц и организаций ClearGPS отправляет введённый запрос и приблизительный центр карты в Photon (OpenStreetMap).",
                            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant
                        )
                        Button(onClick = {
                            preferences.edit().putBoolean(KEY_GEOCODING_CONSENT, true).apply()
                            consent = true
                        }) { Text("Разрешить интернет-поиск") }
                    }
                }
                nearbyCategory != null -> {
                    if (nearbyResults.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Места не найдены в радиусе 10 км", color = colors.onSurfaceVariant)
                        }
                    } else LazyColumn {
                        items(nearbyResults, key = { it.id }) { poi ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onSelectPoi(poi) }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.LocationOn, tint = colors.primary, contentDescription = null)
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text(poi.name, fontWeight = FontWeight.SemiBold)
                                    Text(poi.address ?: poi.category.title, color = colors.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Text("Маршрут", color = colors.primary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                query.trim().length >= 2 -> {
                    when {
                        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            CircularProgressIndicator(Modifier.padding(28.dp))
                        }
                        error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(error!!, color = colors.error)
                        }
                        results.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text("Ничего не найдено. Попробуйте уточнить запрос.", color = colors.onSurfaceVariant)
                        }
                        else -> LazyColumn {
                            items(results, key = { "${it.latitude}:${it.longitude}:${it.name}" }) { result ->
                                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)) {
                                    Row(Modifier.fillMaxWidth().clickable { selectResult(result) },
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.LocationOn, tint = colors.primary, contentDescription = null)
                                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                            Text(result.name, fontWeight = FontWeight.SemiBold)
                                            Text(result.subtitle, style = MaterialTheme.typography.bodySmall,
                                                color = colors.onSurfaceVariant)
                                        }
                                        Text(result.kind, style = MaterialTheme.typography.labelSmall,
                                            color = colors.onSurfaceVariant)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = {
                                            favorites = savePlace(result, KEY_SEARCH_FAVORITES, 30)
                                        }) { Text("★ В избранное") }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                        item {
                            Text("Категории рядом", style = MaterialTheme.typography.titleMedium,
                                color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 10.dp))
                        }
                        items(categories.chunked(2)) { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(bottom = 10.dp)) {
                                pair.forEach { category ->
                                    Surface(
                                        Modifier.weight(1f).clickable { onSelectCategory(category) },
                                        color = colors.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text(category, Modifier.padding(horizontal = 14.dp, vertical = 17.dp),
                                            fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                        if (favorites.isNotEmpty()) {
                            item { Text("Избранное", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) }
                            items(favorites, key = { "fav:$it" }) { encoded ->
                                val place = decodePlace(encoded)
                                if (place != null) Row(
                                    Modifier.fillMaxWidth().clickable { selectResult(place) }.padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Star, tint = colors.primary)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(place.name, fontWeight = FontWeight.SemiBold)
                                        Text(place.subtitle, color = colors.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = {
                                        favorites = favorites.filterNot { it == encoded }
                                        preferences.edit().putStringSet(KEY_SEARCH_FAVORITES, favorites.toSet()).apply()
                                    }) { Text("Убрать") }
                                }
                            }
                        }
                        if (history.isNotEmpty()) {
                            item { Text("Недавние", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) }
                            items(history, key = { "history:$it" }) { encoded ->
                                val place = decodePlace(encoded)
                                if (place != null) Row(
                                    Modifier.fillMaxWidth().clickable { selectResult(place) }.padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.History, tint = colors.onSurfaceVariant)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(place.name)
                                        Text(place.subtitle, style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant)
                                    }
                                    TextButton(onClick = {
                                        favorites = savePlace(place, KEY_SEARCH_FAVORITES, 30)
                                    }) { Text("★") }
                                }
                            }
                            item { TextButton(onClick = {
                                history = emptyList()
                                preferences.edit().remove(KEY_SEARCH_HISTORY).apply()
                            }) { Text("Очистить историю") } }
                        }
                    }
                }
            }
        }
    }
}

private const val KEY_GEOCODING_CONSENT = "navigation_geocoding_search_enabled"
private const val KEY_SEARCH_HISTORY = "navigation_search_history"
private const val KEY_SEARCH_FAVORITES = "navigation_search_favorites"

private fun encodePlace(place: NavigationGeocodingResult): String {
    val raw = listOf(place.name, place.subtitle, place.latitude.toString(), place.longitude.toString(), place.kind)
        .joinToString("|")
    return Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
}

private fun decodePlace(value: String): NavigationGeocodingResult? = runCatching {
    val fields = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8).split("|")
    if (fields.size != 5) return null
    val latitude = fields[2].toDoubleOrNull() ?: return null
    val longitude = fields[3].toDoubleOrNull() ?: return null
    NavigationGeocodingResult(fields[0], fields[1], latitude, longitude, fields[4])
}.getOrNull()

private fun readPlaces(preferences: android.content.SharedPreferences, key: String): List<String> =
    preferences.getStringSet(key, emptySet()).orEmpty().toList().take(30)
