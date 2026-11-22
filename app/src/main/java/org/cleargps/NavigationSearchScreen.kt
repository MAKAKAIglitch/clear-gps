package org.cleargps

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun NavigationSearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSelectCategory: (String) -> Unit
) {
    var showAllCategories by remember { mutableStateOf(false) }
    var showFavorites by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val mainTiles = listOf(
        Triple("Избранное", "★", "saved"),
        Triple("Заправки", "⛽", "fuel"),
        Triple("Общепит", "🍽", "food"),
        Triple("Парковка", "P", "parking"),
        Triple("Продукты", "🛒", "grocery"),
        Triple("Ещё", "＋", "more")
    )
    val categories = listOf(
        "Заправки", "Общепит", "Парковка", "Продукты", "Кофе",
        "Шоппинг", "Аптеки", "Зарядка электромобиля", "Медпомощь",
        "Гостиницы", "Парки", "Банкоматы"
    )

    Surface(modifier = Modifier.fillMaxSize(), color = colors.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.IconButton(onClick = {
                    if (showFavorites || showAllCategories) {
                        showFavorites = false
                        showAllCategories = false
                    } else onBack()
                }) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад")
                }
                Text(
                    when {
                        showFavorites -> "Избранное"
                        showAllCategories -> "Все категории"
                        else -> "Куда едем?"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Адрес или название места") },
                shape = RoundedCornerShape(18.dp)
            )
            Spacer(Modifier.height(18.dp))
            when {
                showFavorites -> {
                    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("☆", style = MaterialTheme.typography.displayMedium, color = colors.onSurfaceVariant)
                            Text("Здесь будут ваши места", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Добавляйте места в избранное из карточки места на карте.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant
                            )
                        }
                    }
                }
                showAllCategories -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        categories.forEachIndexed { index, title ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onSelectCategory(title) }.padding(vertical = 15.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(shape = RoundedCornerShape(12.dp), color = colors.surfaceContainerHigh) {
                                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                                        Text(categoryGlyph(title), style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                                Text(title, modifier = Modifier.weight(1f).padding(start = 14.dp), style = MaterialTheme.typography.titleMedium)
                                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.onSurfaceVariant)
                            }
                            if (index != categories.lastIndex) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outlineVariant.copy(alpha = 0.45f)))
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Быстрый поиск", style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                        mainTiles.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                pair.forEach { (title, glyph, key) ->
                                    Surface(
                                        modifier = Modifier.weight(1f).height(122.dp).clickable {
                                            when (key) {
                                                "more" -> showAllCategories = true
                                                "saved" -> showFavorites = true
                                                else -> onSelectCategory(title)
                                            }
                                        },
                                        shape = RoundedCornerShape(20.dp),
                                        color = colors.surfaceContainerHigh,
                                        tonalElevation = 1.dp
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize().padding(15.dp),
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(glyph, style = MaterialTheme.typography.headlineMedium, color = colors.primary)
                                            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        Text(
                            "Категории помогут быстро найти нужное место рядом.",
                            modifier = Modifier.padding(top = 6.dp, bottom = 20.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun categoryGlyph(title: String): String = when (title) {
    "Заправки" -> "⛽"
    "Общепит" -> "🍽"
    "Парковка" -> "P"
    "Продукты" -> "🛒"
    "Кофе" -> "☕"
    "Шоппинг" -> "🛍"
    "Аптеки" -> "＋"
    "Зарядка электромобиля" -> "⚡"
    "Медпомощь" -> "✚"
    "Гостиницы" -> "⌂"
    "Парки" -> "♧"
    else -> "•"
}
