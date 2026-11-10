package com.minhnhat.echoline.ui.incall

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.minhnhat.echoline.App
import com.minhnhat.echoline.ui.theme.EcholineTheme

/** Màn hình cuộc gọi. Hiện được trên màn hình khóa, tự đóng khi không còn cuộc gọi nào cần hiển thị. */
class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        val graph = (application as App).graph
        setContent {
            EcholineTheme {
                InCallScreen(graph = graph, onNoCalls = { finish() })
            }
        }
    }
}
