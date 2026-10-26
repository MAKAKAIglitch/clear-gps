package org.cleargps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cleargps.core.RiskFeedbackLabel

@Composable
internal fun RiskFeedbackPanel(
    filterState: String,
    riskScore: Int,
    riskSource: String,
    riskReasons: List<String>,
    riskBreakdown: org.cleargps.core.RiskBreakdown?,
    monitorEnabled: Boolean,
    radioOnly: Boolean,
    savedCount: Int,
    onLabel: (RiskFeedbackLabel) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit
) {
    val activeEvent = monitorEnabled && !radioOnly &&
        (filterState == "SUSPECT" || filterState == "ZONE_RISK" || riskScore >= 30)
    val signature = "$filterState|$riskScore|$riskSource|$riskReasons"
    var submittedSignature by remember { mutableStateOf<String?>(null) }
    var pendingLabel by remember { mutableStateOf<RiskFeedbackLabel?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(signature) { submittedSignature = null }

    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.surfaceContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Обратная связь по событию", style = MaterialTheme.typography.titleSmall)
            Text(
                "Твоя метка помогает собрать набор для последующей калибровки, но сама по себе не доказывает атаку и не меняет текущий алгоритм.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
            if (activeEvent && submittedSignature != signature) {
                Text("Текущее событие: $filterState · риск $riskScore/100", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pendingLabel = RiskFeedbackLabel.FALSE_ALARM }, modifier = Modifier.weight(1f)) {
                        Text("Ложная тревога")
                    }
                    FilledTonalButton(onClick = { pendingLabel = RiskFeedbackLabel.SUSPECTED_SPOOFING }, modifier = Modifier.weight(1f)) {
                        Text("Подозрение обосновано")
                    }
                }
            } else if (activeEvent && submittedSignature == signature) {
                Text(
                    "Для этого снимка уже сохранена метка. Она относится к твоей оценке, а не к объективно подтверждённому событию.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
                )
            } else {
                Text("Метки доступны при появлении подозрительного события в режиме GPS/Авто.",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Text("Сохранено меток: $savedCount / 200", style = MaterialTheme.typography.bodySmall)
            Text(
                "Локально хранятся только состояние, баллы и фиксированные коды причин. Экспорт не содержит координат, радиоидентификаторов, сырых измерений или абсолютного времени.",
                style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExport, enabled = savedCount > 0, modifier = Modifier.weight(1f)) {
                    Text("Экспорт JSON")
                }
                TextButton(onClick = { confirmClear = true }, enabled = savedCount > 0) { Text("Очистить") }
            }
        }
    }

    pendingLabel?.let { label ->
        AlertDialog(
            onDismissRequest = { pendingLabel = null },
            title = { Text(if (label == RiskFeedbackLabel.FALSE_ALARM) "Пометить как ложную тревогу?" else "Пометить подозрение как обоснованное?") },
            text = { Text("Сохранится обезличенная сводка текущего снимка. Метка не меняет риск, пороги или поведение mock-позиции.") },
            confirmButton = {
                TextButton(onClick = {
                    onLabel(label)
                    submittedSignature = signature
                    pendingLabel = null
                }) { Text("Сохранить метку") }
            },
            dismissButton = { TextButton(onClick = { pendingLabel = null }) { Text("Отмена") } }
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Удалить всю локальную разметку?") },
            text = { Text("Будут удалены все $savedCount меток. Экспортированный ранее файл не затрагивается.") },
            confirmButton = {
                TextButton(onClick = {
                    onClear()
                    confirmClear = false
                    submittedSignature = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } }
        )
    }
}
