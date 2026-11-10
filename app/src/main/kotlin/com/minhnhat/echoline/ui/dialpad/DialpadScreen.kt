package com.minhnhat.echoline.ui.dialpad

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.R
import com.minhnhat.echoline.data.DefaultSim
import com.minhnhat.echoline.ui.util.CallPlacer
import com.minhnhat.echoline.ui.util.OnResume

private val KEYS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("*", "0", "#")
)

@Composable
fun DialpadScreen(graph: AppGraph, initialNumber: String?, onNumberConsumed: () -> Unit) {
    val context = LocalContext.current
    var number by rememberSaveable { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    OnResume {
        graph.sims.invalidate()
        tick++
    }

    LaunchedEffect(initialNumber) {
        if (initialNumber != null) {
            number = initialNumber
            onNumberConsumed()
        }
    }

    val sims = remember(tick) { graph.sims.activeSims() }
    val preference = remember(tick) { graph.prefs.defaultSim }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = number,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .combinedClickable(
                        onClick = { number = number.dropLast(1) },
                        onLongClick = { number = "" }
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) { Text("⌫", style = MaterialTheme.typography.titleLarge) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in KEYS) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (key in row) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .combinedClickable(
                                    onClick = { number += key },
                                    onLongClick = if (key == "0") ({ number += "+" }) else null
                                ),
                            contentAlignment = Alignment.Center
                        ) { Text(key, style = MaterialTheme.typography.headlineSmall) }
                    }
                }
            }
        }

        if (sims.size >= 2 && preference == DefaultSim.ASK) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (sim in sims) {
                    Button(
                        onClick = { CallPlacer.place(context, graph, number, sim.slot) },
                        enabled = number.isNotEmpty(),
                        modifier = Modifier.weight(1f).height(56.dp)
                    ) { Text(sim.label, maxLines = 1) }
                }
            }
        } else {
            val slot = when (preference) {
                DefaultSim.SLOT_1 -> 1
                DefaultSim.SLOT_2 -> 2
                DefaultSim.ASK -> null
            }
            Button(
                onClick = { CallPlacer.place(context, graph, number, slot) },
                enabled = number.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text(stringResource(R.string.action_call)) }
        }
        Spacer(Modifier.height(4.dp))
    }
}
