package com.minhnhat.echoline.ui.incall

import android.telecom.Call
import android.telecom.CallAudioState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.R
import com.minhnhat.echoline.telecom.CallUi
import com.minhnhat.echoline.telecom.isLiveState
import com.minhnhat.echoline.telecom.isRingingState
import com.minhnhat.echoline.ui.util.formatDuration
import kotlinx.coroutines.delay

private val GREEN = Color(0xFF2E7D32)
private val RED = Color(0xFFC62828)
private val DTMF = listOf(
    listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9'), listOf('*', '0', '#')
)

@Composable
fun InCallScreen(graph: AppGraph, onNoCalls: () -> Unit) {
    val registry = graph.callRegistry
    val calls by registry.calls.collectAsState()
    val audio by registry.audio.collectAsState()

    val visible = calls.filter { !it.hidden }
    val primary = visible.firstOrNull { isRingingState(it.state) }
        ?: visible.firstOrNull { it.state == Call.STATE_ACTIVE }
        ?: visible.firstOrNull()
    val others = visible.filter { it.id != primary?.id }
    val finished = primary == null || visible.none { isLiveState(it.state) }

    // Không còn cuộc gọi nào: đóng sau một nhịp ngắn (cho kịp thấy "đã kết thúc").
    LaunchedEffect(finished) {
        if (finished) {
            delay(if (primary == null) 300L else 1200L)
            onNoCalls()
        }
    }

    var showKeypad by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize()) {
        if (primary == null) {
            Box(modifier = Modifier.fillMaxSize())
            return@Surface
        }
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- thông tin người gọi
            Spacer(Modifier.height(24.dp))
            Text(
                text = primary.name ?: primary.number ?: stringResource(R.string.hidden_number),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            if (primary.name != null && primary.number != null) {
                Text(primary.number, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (primary.simLabel != null) {
                Text(primary.simLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            StatusLine(primary)

            // ---- các cuộc khác
            if (others.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                for (other in others) OtherCallRow(other, graph)
            }

            Spacer(Modifier.weight(1f))

            // ---- điều khiển
            if (primary.incoming && isRingingState(primary.state)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { registry.reject(primary.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = RED, contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(64.dp)
                    ) { Text(stringResource(R.string.decline)) }
                    Button(
                        onClick = { registry.answer(primary.id) },
                        colors = ButtonDefaults.buttonColors(containerColor = GREEN, contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(64.dp)
                    ) { Text(stringResource(R.string.answer)) }
                }
            } else {
                if (showKeypad) {
                    Keypad(onKey = { c, down -> registry.dtmf(primary.id, c, down) })
                    Spacer(Modifier.height(12.dp))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CallToggle(stringResource(R.string.incall_mute), audio.muted, Modifier.weight(1f)) {
                        registry.setMuted(!audio.muted)
                    }
                    val speakerOn = audio.route == CallAudioState.ROUTE_SPEAKER
                    CallToggle(stringResource(R.string.incall_speaker), speakerOn, Modifier.weight(1f)) {
                        registry.setRoute(if (speakerOn) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CallToggle(stringResource(R.string.incall_keypad), showKeypad, Modifier.weight(1f)) { showKeypad = !showKeypad }
                    if (audio.supportedMask and CallAudioState.ROUTE_BLUETOOTH != 0) {
                        val btOn = audio.route == CallAudioState.ROUTE_BLUETOOTH
                        CallToggle(stringResource(R.string.incall_bluetooth), btOn, Modifier.weight(1f)) {
                            registry.setRoute(if (btOn) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_BLUETOOTH)
                        }
                    }
                    if (primary.canHold) {
                        val held = primary.state == Call.STATE_HOLDING
                        CallToggle(stringResource(R.string.incall_hold), held, Modifier.weight(1f)) { registry.hold(primary.id, !held) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { registry.hangup(primary.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = RED, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(64.dp)
                ) { Text(stringResource(R.string.hang_up)) }
            }
        }
    }
}

/** Dòng trạng thái có đồng hồ riêng: mỗi giây chỉ vẽ lại dòng này, không vẽ lại cả màn hình. */
@Composable
private fun StatusLine(call: CallUi) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    Text(statusText(call, now), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun statusText(call: CallUi, now: Long): String = when {
    isRingingState(call.state) -> stringResource(R.string.incall_state_ringing)
    call.state == Call.STATE_ACTIVE -> {
        if (call.connectTimeMs > 0L) formatDuration((now - call.connectTimeMs) / 1000) else stringResource(R.string.incall_state_connected)
    }
    call.state == Call.STATE_HOLDING -> stringResource(R.string.incall_state_holding)
    call.state == Call.STATE_DIALING || call.state == Call.STATE_CONNECTING || call.state == Call.STATE_NEW ->
        stringResource(R.string.incall_state_dialing)
    else -> stringResource(R.string.incall_state_ended)
}

@Composable
private fun CallToggle(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (on) {
        FilledTonalButton(onClick = onClick, modifier = modifier.height(52.dp)) { Text(label, maxLines = 1) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp)) { Text(label, maxLines = 1) }
    }
}

@Composable
private fun OtherCallRow(call: CallUi, graph: AppGraph) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(call.name ?: call.number ?: stringResource(R.string.hidden_number), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (call.state == Call.STATE_HOLDING) stringResource(R.string.incall_state_holding) else stringResource(R.string.incall_state_ringing),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isRingingState(call.state)) {
            OutlinedButton(onClick = { graph.callRegistry.reject(call.id) }) { Text(stringResource(R.string.decline)) }
            Button(onClick = { graph.callRegistry.answer(call.id) }) { Text(stringResource(R.string.answer)) }
        } else {
            OutlinedButton(onClick = { graph.callRegistry.hold(call.id, false) }) { Text(stringResource(R.string.incall_switch)) }
            OutlinedButton(onClick = { graph.callRegistry.hangup(call.id) }) { Text(stringResource(R.string.hang_up)) }
        }
    }
}

@Composable
private fun Keypad(onKey: (Char, Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in DTMF) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (key in row) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .pointerInput(key) {
                                detectTapGestures(onPress = {
                                    onKey(key, true)
                                    tryAwaitRelease()
                                    onKey(key, false)
                                })
                            },
                        contentAlignment = Alignment.Center
                    ) { Text(key.toString(), style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
}
