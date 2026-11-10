package com.minhnhat.echoline.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.R
import com.minhnhat.echoline.data.DefaultSim
import com.minhnhat.echoline.domain.model.AutoAnswerSettings
import com.minhnhat.echoline.domain.model.BlockStyle
import com.minhnhat.echoline.domain.model.FilterSettings
import com.minhnhat.echoline.ui.util.ChipsRow
import com.minhnhat.echoline.ui.util.ScrollColumn
import com.minhnhat.echoline.ui.util.SectionTitle
import com.minhnhat.echoline.ui.util.StepperRow
import com.minhnhat.echoline.ui.util.SwitchRow
import kotlinx.coroutines.launch

private val HISTORY_PRESETS = listOf(0, 60, 360, 1440, 4320, 10080, 43200)

@Composable
fun SettingsScreen(
    graph: AppGraph,
    onOpenOnboarding: () -> Unit,
    onOpenDecisionLog: () -> Unit,
    onOpenDiag: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val config by graph.configStore.config.collectAsState()
    val s = config.settings
    val a = s.autoAnswer
    var simPrefTick by remember { mutableIntStateOf(0) }

    fun change(transform: (FilterSettings) -> FilterSettings) {
        scope.launch { graph.configStore.update { it.copy(settings = transform(it.settings)) } }
    }

    fun changeAuto(transform: (AutoAnswerSettings) -> AutoAnswerSettings) {
        change { it.copy(autoAnswer = transform(it.autoAnswer)) }
    }

    ScrollColumn(modifier = Modifier.fillMaxWidth()) {
        SectionTitle(stringResource(R.string.settings_filter))
        SwitchRow(
            stringResource(R.string.setting_block_hidden), stringResource(R.string.setting_block_hidden_desc),
            s.blockHidden
        ) { v -> change { it.copy(blockHidden = v) } }
        SwitchRow(
            stringResource(R.string.setting_block_unknown), stringResource(R.string.setting_block_unknown_desc),
            s.blockUnknown
        ) { v -> change { it.copy(blockUnknown = v) } }
        Text(
            stringResource(R.string.setting_block_style),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)
        )
        ChipsRow(
            options = listOf(
                BlockStyle.REJECT to stringResource(R.string.block_style_reject),
                BlockStyle.SILENCE to stringResource(R.string.block_style_silence)
            ),
            selected = s.blockStyle,
            onSelect = { v -> change { it.copy(blockStyle = v) } },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        StepperRow(
            stringResource(R.string.setting_emergency_grace),
            if (s.emergencyGraceDays <= 0) stringResource(R.string.unit_off) else pluralStringResource(R.plurals.unit_days, s.emergencyGraceDays, s.emergencyGraceDays),
            onMinus = { change { it.copy(emergencyGraceDays = (it.emergencyGraceDays - 1).coerceAtLeast(0)) } },
            onPlus = { change { it.copy(emergencyGraceDays = (it.emergencyGraceDays + 1).coerceAtMost(30)) } }
        )

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
        SectionTitle(stringResource(R.string.settings_auto_answer))
        SwitchRow(stringResource(R.string.setting_auto_enabled), stringResource(R.string.setting_auto_enabled_desc), a.enabled) { v ->
            changeAuto { it.copy(enabled = v) }
        }
        StepperRow(
            stringResource(R.string.setting_auto_delay),
            if (a.delaySec <= 0) stringResource(R.string.unit_hidden) else stringResource(R.string.unit_seconds, a.delaySec),
            onMinus = { changeAuto { it.copy(delaySec = (it.delaySec - 1).coerceAtLeast(0)) } },
            onPlus = { changeAuto { it.copy(delaySec = (it.delaySec + 1).coerceAtMost(120)) } }
        )
        Text(
            stringResource(R.string.setting_auto_delay_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        SwitchRow(stringResource(R.string.setting_auto_mute), stringResource(R.string.setting_auto_mute_desc), a.muteMic) { v ->
            changeAuto { it.copy(muteMic = v) }
        }
        StepperRow(
            stringResource(R.string.setting_auto_hangup),
            stringResource(R.string.unit_seconds, a.hangUpAfterSec),
            onMinus = { changeAuto { it.copy(hangUpAfterSec = (it.hangUpAfterSec - 5).coerceAtLeast(5)) } },
            onPlus = { changeAuto { it.copy(hangUpAfterSec = (it.hangUpAfterSec + 5).coerceAtMost(3600)) } }
        )
        val histLabel = historyLabel(a.historyWindowMin)
        StepperRow(
            stringResource(R.string.setting_auto_history), histLabel,
            onMinus = { changeAuto { it.copy(historyWindowMin = stepPreset(it.historyWindowMin, -1)) } },
            onPlus = { changeAuto { it.copy(historyWindowMin = stepPreset(it.historyWindowMin, 1)) } }
        )
        Text(
            stringResource(R.string.setting_auto_history_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
        SectionTitle(stringResource(R.string.settings_calls))
        Text(
            stringResource(R.string.setting_default_sim),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)
        )
        val simPref = remember(simPrefTick) { graph.prefs.defaultSim }
        ChipsRow(
            options = listOf(
                DefaultSim.ASK to stringResource(R.string.sim_ask),
                DefaultSim.SLOT_1 to (graph.sims.labelOf(1) ?: "SIM 1"),
                DefaultSim.SLOT_2 to (graph.sims.labelOf(2) ?: "SIM 2")
            ),
            selected = simPref,
            onSelect = { v ->
                graph.prefs.defaultSim = v
                simPrefTick++
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
        SectionTitle(stringResource(R.string.settings_tools))
        LinkRow(stringResource(R.string.link_permissions), onOpenOnboarding)
        LinkRow(stringResource(R.string.link_decision_log), onOpenDecisionLog)
        // Nhật ký lỗi: gỡ bỏ thì xóa dòng này (xem README).
        LinkRow(stringResource(R.string.link_diag), onOpenDiag)
    }
}

@Composable
private fun LinkRow(title: String, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun historyLabel(minutes: Int): String = when {
    minutes <= 0 -> stringResource(R.string.unit_off)
    minutes % 1440 == 0 -> pluralStringResource(R.plurals.unit_days, minutes / 1440, minutes / 1440)
    minutes % 60 == 0 -> pluralStringResource(R.plurals.unit_hours, minutes / 60, minutes / 60)
    else -> stringResource(R.string.unit_minutes, minutes)
}

/** Đi tới mức cài sẵn kế tiếp (hoặc trước đó) so với giá trị hiện tại. */
private fun stepPreset(current: Int, direction: Int): Int {
    return if (direction > 0) {
        HISTORY_PRESETS.firstOrNull { it > current } ?: HISTORY_PRESETS.last()
    } else {
        HISTORY_PRESETS.lastOrNull { it < current } ?: HISTORY_PRESETS.first()
    }
}
