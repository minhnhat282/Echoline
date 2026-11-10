package com.minhnhat.echoline.diag.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minhnhat.echoline.R
import com.minhnhat.echoline.diag.Diag
import com.minhnhat.echoline.diag.Level
import com.minhnhat.echoline.diag.impl.DiagStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn hình xem / lưu / xóa nhật ký lỗi. Gỡ bỏ: xóa thư mục diag/ui và dòng tham chiếu trong AppRoot.kt,
 * SettingsScreen.kt (xem README).
 */
@Composable
fun DiagScreen(store: DiagStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var verbose by remember { mutableStateOf(Diag.minLevel == Level.DEBUG) }
    val text = remember(refresh) { store.readTail(MAX_SHOWN_CHARS) }
    val size = remember(refresh) { store.sizeBytes() }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(store.readFull().toByteArray(Charsets.UTF_8)) } != null
                    } catch (e: Exception) {
                        false
                    }
                }
                Toast.makeText(context, if (ok) R.string.diag_saved else R.string.diag_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
            Text(stringResource(R.string.link_diag), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.diag_verbose), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = verbose, onCheckedChange = {
                verbose = it
                Diag.minLevel = if (it) Level.DEBUG else Level.INFO
            })
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.action_refresh)) }
            TextButton(onClick = { saveLauncher.launch("echoline-diag.txt") }) { Text(stringResource(R.string.diag_save)) }
            TextButton(onClick = {
                store.clear()
                refresh++
            }) { Text(stringResource(R.string.action_clear)) }
        }
        Text(
            stringResource(R.string.diag_size, size / 1024),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Text(
            text = text.ifBlank { stringResource(R.string.diag_empty) },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)
        )
    }
}

private const val MAX_SHOWN_CHARS = 60_000
