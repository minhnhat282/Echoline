package com.minhnhat.echoline.ui.log

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.R
import com.minhnhat.echoline.data.DecisionEntry
import com.minhnhat.echoline.ui.util.formatDateTime

@Composable
fun DecisionLogScreen(graph: AppGraph, onBack: () -> Unit) {
    val entries by graph.decisionLog.entries.collectAsState()
    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            Text(stringResource(R.string.link_decision_log), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { graph.decisionLog.clear() }) { Text(stringResource(R.string.action_clear)) }
        }
        Text(
            stringResource(R.string.decision_log_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.decision_log_empty), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries) { entry ->
                    EntryRow(entry, graph)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun decisionLabel(code: String): String = when (code) {
    "RING" -> stringResource(R.string.decision_ring)
    "BLOCK" -> stringResource(R.string.decision_block)
    "SILENCE" -> stringResource(R.string.decision_silence)
    "AUTO_HIDDEN" -> stringResource(R.string.decision_auto_hidden)
    "AUTO_UI" -> stringResource(R.string.decision_auto_ui)
    else -> code
}

@Composable
private fun EntryRow(e: DecisionEntry, graph: AppGraph) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        val who = e.number ?: stringResource(R.string.hidden_number)
        val sim = e.simSlot?.let { " · " + (graph.sims.labelOf(it) ?: "SIM $it") }.orEmpty()
        Text("$who$sim", style = MaterialTheme.typography.bodyLarge)
        Text(
            "${decisionLabel(e.decision)} · ${formatDateTime(e.timeMs)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(e.reason, style = MaterialTheme.typography.bodySmall)
        val meta = buildString {
            append("${e.elapsedMs} ms · ${e.source}")
            if (!e.note.isNullOrBlank()) append(" · ").append(e.note)
        }
        Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
