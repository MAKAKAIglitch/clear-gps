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
            BreakdownRow("GPS ↔ локальная радио-оценка", breakdown.radioPositionEvaluated, breakdown.radioPositionScore,
                breakdown.radioPositionReason, "Нет достаточно свежей радио-оценки с приемлемой точностью для сравнения.")
            BreakdownRow("Физический GNSS ↔ скорость OBD-II", breakdown.obdSpeedEvaluated, breakdown.obdSpeedScore,
                breakdown.obdSpeedReason, "Нет свежей скорости OBD-II либо качество/время пары GNSS–OBD недостаточно.")
            BreakdownRow("Сырые GNSS/RF-признаки", breakdown.gnssSignalEvaluated, breakdown.gnssSignalScore,
                breakdown.gnssSignalReason, "Нет свежих пригодных сигнальных метрик GNSS.")

            if (breakdown.motionCrossSourceBonus > 0) Text(
                "Подтверждение независимым источником: +${breakdown.motionCrossSourceBonus} внутренних баллов за совместное срабатывание траектории и расхождения источников.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
            if (breakdown.radioCorroborationBonus > 0) Text(
                "Поддержка локальной радио-оценки: +${breakdown.radioCorroborationBonus} внутренних баллов; радио не подтверждает подмену самостоятельно.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant
            )
            if (breakdown.obdSpeedCorroborationBonus > 0) Text(
                "Поддержка расхождения OBD-II/GNSS: +${breakdown.obdSpeedCorroborationBonus} внутренних баллов; OBD-II не подтверждает подмену самостоятельно.",
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
