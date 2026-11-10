package com.minhnhat.echoline.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.minhnhat.echoline.App
import com.minhnhat.echoline.diag.Diag

/** Nhận thao tác Trả lời / Từ chối / Cúp từ nút trong thông báo. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val graph = (context.applicationContext as App).graph
            val id = intent.getIntExtra(EXTRA_ID, -1)
            when (intent.action) {
                ACTION_ANSWER -> graph.callRegistry.answer(id)
                ACTION_DECLINE -> graph.callRegistry.reject(id)
                ACTION_HANGUP -> graph.callRegistry.hangup(id)
            }
        } catch (e: Exception) {
            Diag.e("notify", "Lỗi khi xử lý thao tác từ thông báo", e)
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.minhnhat.echoline.action.ANSWER"
        const val ACTION_DECLINE = "com.minhnhat.echoline.action.DECLINE"
        const val ACTION_HANGUP = "com.minhnhat.echoline.action.HANGUP"
        const val EXTRA_ID = "call_id"
    }
}
