package com.minhnhat.echoline.ui.recents

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.R
import com.minhnhat.echoline.data.ruleForNumber
import com.minhnhat.echoline.data.upsert
import com.minhnhat.echoline.diag.Diag
import com.minhnhat.echoline.domain.model.RuleAction
import com.minhnhat.echoline.ui.util.CallPlacer
import com.minhnhat.echoline.ui.util.OnResume
import com.minhnhat.echoline.ui.util.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private class RecentItem(
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
    val autoAnswered: Boolean
)

@Composable
fun RecentsScreen(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<RecentItem>?>(null) }
    OnResume { tick++ }

    val permitted = remember(tick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
    }
    LaunchedEffect(tick, permitted) {
        items = if (permitted) load(context, graph) else emptyList()
    }

    val list = items
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !permitted -> Centered(stringResource(R.string.recents_need_permission))
            list == null -> Unit
            list.isEmpty() -> Centered(stringResource(R.string.recents_empty))
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(list) { item ->
                    RecentRow(item, onCall = { CallPlacer.place(context, graph, item.number, null) }, onRule = { action ->
                        val rule = ruleForNumber(item.number, action, graph.sims.region())
                        if (rule == null) {
                            Toast.makeText(context, R.string.rule_invalid_number, Toast.LENGTH_SHORT).show()
                        } else {
                            scope.launch { graph.configStore.update { it.upsert(rule) } }
                            Toast.makeText(context, R.string.rule_added, Toast.LENGTH_SHORT).show()
                        }
                    })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RecentRow(item: RecentItem, onCall: () -> Unit, onRule: (RuleAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val typeLabel = when (item.type) {
        CallLog.Calls.INCOMING_TYPE -> stringResource(R.string.call_type_incoming)
        CallLog.Calls.OUTGOING_TYPE -> stringResource(R.string.call_type_outgoing)
        CallLog.Calls.MISSED_TYPE -> stringResource(R.string.call_type_missed)
        CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> stringResource(R.string.call_type_blocked)
        else -> stringResource(R.string.call_type_other)
    }
    Box {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { menu = true }.padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                item.name ?: item.number.ifBlank { stringResource(R.string.hidden_number) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val extra = if (item.autoAnswered) " · " + stringResource(R.string.label_auto_answered) else ""
            Text(
                "$typeLabel · ${formatDateTime(item.date)}$extra",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (item.number.isNotBlank()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_call_back)) }, onClick = { menu = false; onCall() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_block_number)) }, onClick = { menu = false; onRule(RuleAction.BLOCK) })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_auto_answer_number)) }, onClick = { menu = false; onRule(RuleAction.AUTO_ANSWER) })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_allow_number)) }, onClick = { menu = false; onRule(RuleAction.ALLOW) })
            }
        }
    }
}

private suspend fun load(context: Context, graph: AppGraph): List<RecentItem> = withContext(Dispatchers.IO) {
    val marks = graph.prefs.autoAnswerMarks()
    val out = ArrayList<RecentItem>()
    try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            null, null, "${CallLog.Calls.DATE} DESC"
        )?.use { c ->
            val iNumber = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val iName = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val iType = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val iDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            while (c.moveToNext() && out.size < LIMIT) {
                val date = c.getLong(iDate)
                out.add(
                    RecentItem(
                        number = c.getString(iNumber).orEmpty(),
                        name = c.getString(iName)?.takeIf { it.isNotBlank() },
                        type = c.getInt(iType),
                        date = date,
                        autoAnswered = marks.any { abs(it - date) <= MARK_TOLERANCE_MS }
                    )
                )
            }
        }
    } catch (e: Exception) {
        Diag.e("recents-ui", "Không đọc được nhật ký cuộc gọi", e)
    }
    out
}

private const val LIMIT = 100
private const val MARK_TOLERANCE_MS = 20_000L
