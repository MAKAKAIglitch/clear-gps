package org.cleargps

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun OfflineDemPanel() {
    val context = LocalContext.current
    val store = remember(context) {
        SrtmHgtTilePackStore(File(context.filesDir, "dem/srtm"))
    }
    var inventory by remember(context) { mutableStateOf(store.inventory()) }
    var status by remember(context) { mutableStateOf<String?>(null) }
    var importing by remember(context) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !importing) {
            importing = true
            status = "Проверяю архив…"
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val input = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                    if (input == null) {
                        SrtmHgtTileImportResult(0, 0, "Не удалось открыть выбранный файл.")
                    } else store.importZip(input)
                }
                inventory = withContext(Dispatchers.IO) { store.inventory() }
                status = if (result.succeeded) {
                    "Импортировано тайлов: ${result.importedTiles} (${formatDemBytes(result.importedBytes)}). Всего в хранилище: ${inventory.tileCount}."
                } else {
                    buildString {
                        append(result.error ?: "Импорт не выполнен.")
                        if (result.importedTiles > 0) append(" Уже сохранено до ошибки: ${result.importedTiles}.")
                    }
                }
                importing = false
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.surfaceContainer
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Офлайн-рельеф (DEM)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Локальные SRTM HGT-тайлы. Выберите ZIP с файлами .hgt; автоматическое скачивание не выполняется.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
            Text(
                "Установлено: ${inventory.tileCount} тайлов · ${formatDemBytes(inventory.totalBytes)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            OutlinedButton(
                onClick = {
                    picker.launch(arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream",
                        "*/*"
                    ))
                },
                enabled = !importing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (importing) "Импортирую…" else "Импортировать ZIP с HGT-тайлами")
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
            Text(
                "Проверяются географические имена и размеры тайлов. Данные копируются во внутреннее хранилище приложения и не отправляются в сеть. Перед распространением набора проверьте его лицензию и атрибуцию.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}

private fun formatDemBytes(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f MiB", bytes.toDouble() / (1024.0 * 1024.0))
