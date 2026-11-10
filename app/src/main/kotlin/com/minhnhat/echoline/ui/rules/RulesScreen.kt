package com.minhnhat.echoline.ui.rules

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.R
import com.minhnhat.echoline.data.SystemBlockedImporter
import com.minhnhat.echoline.data.removeRule
import com.minhnhat.echoline.data.upsert
import com.minhnhat.echoline.diag.Diag
import com.minhnhat.echoline.domain.codec.RulesTransfer
import com.minhnhat.echoline.domain.codec.TransferParseResult
import com.minhnhat.echoline.domain.model.Rule
import com.minhnhat.echoline.domain.model.RuleAction
import com.minhnhat.echoline.domain.model.SimScope
import com.minhnhat.echoline.domain.number.PatternParseResult
import com.minhnhat.echoline.domain.number.PatternParser
import com.minhnhat.echoline.ui.util.ChipsRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private class EditState(val rule: Rule?)

@Composable
fun RulesScreen(graph: AppGraph) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by graph.configStore.config.collectAsState()
    val problem by graph.configStore.problem.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var edit by remember { mutableStateOf<EditState?>(null) }
    var menu by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch { exportTo(context, graph, uri) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch { importFrom(context, graph, uri) }
    }

    val rules = remember(config.rules, query) {
        val q = query.trim()
        config.rules
            .filter { q.isEmpty() || it.display.contains(q, true) || it.pattern.contains(q) || (it.note ?: "").contains(q, true) }
            .sortedByDescending { it.createdAt }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    label = { Text(stringResource(R.string.rules_search)) }, modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.rules_export)) }, onClick = {
                            menu = false
                            exportLauncher.launch("echoline-rules.json")
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.rules_import)) }, onClick = {
                            menu = false
                            importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.rules_import_system)) }, onClick = {
                            menu = false
                            scope.launch { importSystemBlocked(context, graph) }
                        })
                    }
                }
            }
            if (problem != null) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(problem.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { graph.configStore.dismissProblem() }) { Text(stringResource(R.string.action_dismiss)) }
                    }
                }
            }
            if (rules.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.rules_empty), style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rules, key = { it.id }) { rule ->
                        RuleRow(rule, graph) { edit = EditState(rule) }
                        HorizontalDivider()
                    }
                    item { Spacer(Modifier.height(88.dp)) }
                }
            }
        }
        FloatingActionButton(
            onClick = { edit = EditState(null) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.rules_add)) }
    }

    val state = edit
    if (state != null) {
        RuleDialog(
            graph = graph,
            existing = state.rule,
            onDismiss = { edit = null },
            onSave = { rule ->
                scope.launch { graph.configStore.update { it.upsert(rule) } }
                edit = null
            },
            onDelete = { id ->
                scope.launch { graph.configStore.update { it.removeRule(id) } }
                edit = null
            }
        )
    }
}

@Composable
private fun actionLabel(action: RuleAction): String = when (action) {
    RuleAction.BLOCK -> stringResource(R.string.action_block)
    RuleAction.AUTO_ANSWER -> stringResource(R.string.action_auto_answer)
    RuleAction.ALLOW -> stringResource(R.string.action_allow)
}

@Composable
private fun RuleRow(rule: Rule, graph: AppGraph, onClick: () -> Unit) {
    val simText = when (rule.sim) {
        SimScope.ANY -> null
        SimScope.SLOT_1 -> graph.sims.labelOf(1)
        SimScope.SLOT_2 -> graph.sims.labelOf(2)
    }
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(rule.display, style = MaterialTheme.typography.bodyLarge)
        val parts = listOfNotNull(actionLabel(rule.action), simText, rule.note?.takeIf { it.isNotBlank() })
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RuleDialog(
    graph: AppGraph,
    existing: Rule?,
    onDismiss: () -> Unit,
    onSave: (Rule) -> Unit,
    onDelete: (String) -> Unit
) {
    var text by remember { mutableStateOf(existing?.display ?: "") }
    var action by remember { mutableStateOf(existing?.action ?: RuleAction.BLOCK) }
    var sim by remember { mutableStateOf(existing?.sim ?: SimScope.ANY) }
    var note by remember { mutableStateOf(existing?.note ?: "") }

    val parsed = remember(text) { PatternParser.parse(text, graph.sims.region()) }
    val valid = parsed as? PatternParseResult.Valid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (existing == null) R.string.rules_add else R.string.rules_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text(stringResource(R.string.rule_pattern)) },
                    supportingText = {
                        val msg = when {
                            text.isBlank() -> stringResource(R.string.rule_pattern_hint)
                            parsed is PatternParseResult.Invalid -> stringResource(R.string.rule_err_invalid)
                            parsed is PatternParseResult.TooShort -> stringResource(R.string.rule_err_short)
                            parsed is PatternParseResult.InternationalWildcard -> stringResource(R.string.rule_err_international)
                            valid != null && valid.isWildcard && PatternParser.isBroadWildcard(valid.pattern) -> stringResource(R.string.rule_warn_broad)
                            else -> stringResource(R.string.rule_pattern_hint)
                        }
                        Text(msg)
                    },
                    isError = text.isNotBlank() && valid == null,
                    modifier = Modifier.fillMaxWidth()
                )
                ChipsRow(
                    options = listOf(
                        RuleAction.BLOCK to stringResource(R.string.action_block),
                        RuleAction.AUTO_ANSWER to stringResource(R.string.action_auto_answer),
                        RuleAction.ALLOW to stringResource(R.string.action_allow)
                    ),
                    selected = action, onSelect = { action = it }
                )
                ChipsRow(
                    options = listOf(
                        SimScope.ANY to stringResource(R.string.sim_any),
                        SimScope.SLOT_1 to (graph.sims.labelOf(1) ?: "SIM 1"),
                        SimScope.SLOT_2 to (graph.sims.labelOf(2) ?: "SIM 2")
                    ),
                    selected = sim, onSelect = { sim = it }
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it }, singleLine = true,
                    label = { Text(stringResource(R.string.rule_note)) }, modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid != null,
                onClick = {
                    val v = valid ?: return@TextButton
                    onSave(
                        Rule(
                            id = existing?.id ?: UUID.randomUUID().toString(),
                            pattern = v.pattern,
                            display = text.trim(),
                            action = action,
                            sim = sim,
                            note = note.trim().ifEmpty { null },
                            createdAt = existing?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                }
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { onDelete(existing.id) }) { Text(stringResource(R.string.action_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    )
}

private suspend fun exportTo(context: android.content.Context, graph: AppGraph, uri: Uri) {
    val ok = withContext(Dispatchers.IO) {
        try {
            val json = RulesTransfer.export(graph.configStore.config.value.rules, System.currentTimeMillis())
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null
        } catch (e: Exception) {
            Diag.e("rules-ui", "Xuất quy tắc lỗi", e)
            false
        }
    }
    Toast.makeText(context, if (ok) R.string.rules_export_ok else R.string.rules_export_fail, Toast.LENGTH_SHORT).show()
}

private suspend fun importFrom(context: android.content.Context, graph: AppGraph, uri: Uri) {
    val text = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            Diag.e("rules-ui", "Đọc tệp nhập lỗi", e)
            null
        }
    }
    if (text == null) {
        Toast.makeText(context, R.string.rules_import_fail, Toast.LENGTH_LONG).show()
        return
    }
    when (val result = RulesTransfer.parse(text, graph.sims.region(), System.currentTimeMillis())) {
        is TransferParseResult.Invalid -> Toast.makeText(context, R.string.rules_import_invalid, Toast.LENGTH_LONG).show()
        is TransferParseResult.Ok -> {
            graph.configStore.update { cfg -> result.rules.fold(cfg) { acc, rule -> acc.upsert(rule) } }
            Toast.makeText(
                context,
                context.resources.getQuantityString(R.plurals.rules_import_result, result.rules.size, result.rules.size, result.skipped),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

private suspend fun importSystemBlocked(context: android.content.Context, graph: AppGraph) {
    val numbers = withContext(Dispatchers.IO) { SystemBlockedImporter.read(context) }
    if (numbers == null) {
        Toast.makeText(context, R.string.rules_import_system_fail, Toast.LENGTH_LONG).show()
        return
    }
    val region = graph.sims.region()
    var added = 0
    graph.configStore.update { cfg ->
        var acc = cfg
        for (n in numbers) {
            val rule = com.minhnhat.echoline.data.ruleForNumber(n, RuleAction.BLOCK, region) ?: continue
            acc = acc.upsert(rule)
            added++
        }
        acc
    }
    Toast.makeText(context, context.resources.getQuantityString(R.plurals.rules_import_system_result, numbers.size, added, numbers.size), Toast.LENGTH_LONG).show()
}
