package com.minhnhat.echoline.ui.onboarding

import android.app.role.RoleManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.R
import com.minhnhat.echoline.ui.util.OnResume
import com.minhnhat.echoline.ui.util.ScrollColumn
import com.minhnhat.echoline.ui.util.SystemAccess

@Composable
fun OnboardingScreen(graph: AppGraph, onClose: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    OnResume { tick++ }

    val refresh = {
        graph.onPermissionsChanged()
        tick++
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    val settingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    // Đọc lại trạng thái mỗi khi tick đổi.
    val isDefault = remember(tick) { SystemAccess.isDefaultDialer(context) }
    val isScreening = remember(tick) { SystemAccess.isRoleHeld(context, RoleManager.ROLE_CALL_SCREENING) }
    val missing = remember(tick) { SystemAccess.missingPermissions(context) }
    val fullScreen = remember(tick) { SystemAccess.canUseFullScreenIntent(context) }

    ScrollColumn(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.onboarding_intro), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        StepCard(
            title = stringResource(R.string.step_default_dialer),
            description = stringResource(R.string.step_default_dialer_desc),
            done = isDefault,
            buttonText = stringResource(R.string.action_grant),
            onClick = {
                SystemAccess.roleIntent(context, RoleManager.ROLE_DIALER)?.let { roleLauncher.launch(it) }
            }
        )
        StepCard(
            title = stringResource(R.string.step_screening),
            description = stringResource(R.string.step_screening_desc),
            done = isScreening,
            buttonText = stringResource(R.string.action_grant),
            onClick = {
                SystemAccess.roleIntent(context, RoleManager.ROLE_CALL_SCREENING)?.let { roleLauncher.launch(it) }
            }
        )
        StepCard(
            title = stringResource(R.string.step_permissions),
            description = stringResource(R.string.step_permissions_desc),
            done = missing.isEmpty(),
            buttonText = stringResource(R.string.action_grant),
            onClick = { permissionLauncher.launch(missing.toTypedArray()) }
        )
        StepCard(
            title = stringResource(R.string.step_fullscreen),
            description = stringResource(R.string.step_fullscreen_desc),
            done = fullScreen,
            buttonText = stringResource(R.string.action_open_settings),
            onClick = { settingsLauncher.launch(SystemAccess.fullScreenIntentSettings(context)) }
        )

        Spacer(Modifier.height(16.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (isDefault && missing.isEmpty()) R.string.action_done else R.string.action_skip))
        }
    }
}

@Composable
private fun StepCard(title: String, description: String, done: Boolean, buttonText: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (done) "✓" else "○", style = MaterialTheme.typography.titleMedium)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!done) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onClick) { Text(buttonText) }
            }
        }
    }
}
