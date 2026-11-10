package com.minhnhat.echoline.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.R
import com.minhnhat.echoline.diag.impl.DiagSetup
import com.minhnhat.echoline.diag.ui.DiagScreen
import com.minhnhat.echoline.ui.dialpad.DialpadScreen
import com.minhnhat.echoline.ui.log.DecisionLogScreen
import com.minhnhat.echoline.ui.onboarding.OnboardingScreen
import com.minhnhat.echoline.ui.recents.RecentsScreen
import com.minhnhat.echoline.ui.rules.RulesScreen
import com.minhnhat.echoline.ui.settings.SettingsScreen
import com.minhnhat.echoline.ui.util.SystemAccess

enum class Tab { DIAL, RECENTS, RULES, SETTINGS }

enum class Overlay { ONBOARDING, DECISION_LOG, DIAG }

@Composable
fun AppRoot(graph: AppGraph, initialNumber: String?, onNumberConsumed: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.DIAL) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    var onboardingChecked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!onboardingChecked) {
            onboardingChecked = true
            if (!SystemAccess.isDefaultDialer(context)) overlay = Overlay.ONBOARDING
        }
    }
    LaunchedEffect(initialNumber) {
        if (initialNumber != null) {
            tab = Tab.DIAL
            overlay = null
        }
    }

    BackHandler(enabled = overlay != null) { overlay = null }

    when (overlay) {
        Overlay.ONBOARDING -> OnboardingScreen(graph = graph, onClose = { overlay = null })
        Overlay.DECISION_LOG -> DecisionLogScreen(graph = graph, onBack = { overlay = null })
        // Nhật ký lỗi: gỡ bỏ thì xóa nhánh này (xem README).
        Overlay.DIAG -> DiagScreen(store = DiagSetup.store, onBack = { overlay = null })
        null -> Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.DIAL, onClick = { tab = Tab.DIAL },
                        icon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_dial)) }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.RECENTS, onClick = { tab = Tab.RECENTS },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_recents)) }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.RULES, onClick = { tab = Tab.RULES },
                        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_rules)) }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS, onClick = { tab = Tab.SETTINGS },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_settings)) }
                    )
                }
            }
        ) { inner ->
            Box(modifier = Modifier.padding(inner)) {
                when (tab) {
                    Tab.DIAL -> DialpadScreen(graph, initialNumber, onNumberConsumed)
                    Tab.RECENTS -> RecentsScreen(graph)
                    Tab.RULES -> RulesScreen(graph)
                    Tab.SETTINGS -> SettingsScreen(
                        graph = graph,
                        onOpenOnboarding = { overlay = Overlay.ONBOARDING },
                        onOpenDecisionLog = { overlay = Overlay.DECISION_LOG },
                        onOpenDiag = { overlay = Overlay.DIAG }
                    )
                }
            }
        }
    }
}
