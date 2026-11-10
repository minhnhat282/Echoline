package com.minhnhat.echoline.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.minhnhat.echoline.App
import com.minhnhat.echoline.ui.theme.EcholineTheme

class MainActivity : ComponentActivity() {
    /** Số được truyền vào từ ứng dụng khác (tel:...), điền sẵn vào bàn phím. */
    private var dialNumber by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        dialNumber = numberFrom(intent)
        val graph = (application as App).graph
        setContent {
            EcholineTheme {
                AppRoot(graph = graph, initialNumber = dialNumber, onNumberConsumed = { dialNumber = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dialNumber = numberFrom(intent)
    }

    private fun numberFrom(intent: Intent?): String? {
        val action = intent?.action
        if (action != Intent.ACTION_DIAL && action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        return if (data.scheme == "tel") data.schemeSpecificPart else null
    }
}
