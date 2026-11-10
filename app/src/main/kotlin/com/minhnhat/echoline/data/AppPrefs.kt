package com.minhnhat.echoline.data

import android.content.Context
import androidx.core.content.edit

enum class DefaultSim { ASK, SLOT_1, SLOT_2 }

/** Các giá trị nhỏ, đọc/ghi nhanh. Quy tắc và cài đặt lọc KHÔNG nằm ở đây (xem FilterConfigStore). */
class AppPrefs(context: Context) {
    private val sp = context.getSharedPreferences("echoline", Context.MODE_PRIVATE)

    var defaultSim: DefaultSim
        get() = try {
            DefaultSim.valueOf(sp.getString(KEY_DEFAULT_SIM, null) ?: DefaultSim.ASK.name)
        } catch (e: IllegalArgumentException) {
            DefaultSim.ASK
        }
        set(value) {
            sp.edit { putString(KEY_DEFAULT_SIM, value.name) }
        }

    /** Thời điểm người dùng gọi số khẩn cấp gần nhất (ms). Ghi đồng bộ để không mất khi tiến trình bị kill. */
    var emergencyCallAt: Long
        get() = sp.getLong(KEY_EMERGENCY_AT, 0L)
        set(value) {
            sp.edit(commit = true) { putLong(KEY_EMERGENCY_AT, value) } // ghi đồng bộ có chủ ý: không được mất khi tiến trình bị kill
        }

    /**
     * Ứng dụng đã tự tắt micro cho một cuộc tự trả lời (để khôi phục nếu tiến trình bị kill giữa chừng).
     * Ghi đồng bộ (commit) có chủ ý: đây là cờ an toàn, không được mất nếu tiến trình bị kill ngay sau đó. Chỉ được ghi sau khi lệnh
     * nghe đã gửi (ApplyMute chạy sau AnswerCall) nên không làm chậm việc nghe máy.
     */
    var micMutedByUs: Boolean
        get() = sp.getBoolean(KEY_MIC_MUTED, false)
        set(value) {
            sp.edit(commit = true) { putBoolean(KEY_MIC_MUTED, value) }
        }

    /** Thời điểm (ms) các cuộc đã được tự trả lời, giữ 200 mục gần nhất, để gắn nhãn trong "Gần đây". */
    fun autoAnswerMarks(): List<Long> =
        (sp.getString(KEY_MARKS, "") ?: "").split(',').mapNotNull { it.toLongOrNull() }

    @Synchronized
    fun addAutoAnswerMark(timeMs: Long) {
        val list = (autoAnswerMarks() + timeMs).takeLast(MAX_MARKS)
        sp.edit { putString(KEY_MARKS, list.joinToString(",")) }
    }

    private companion object {
        const val KEY_DEFAULT_SIM = "defaultSim"
        const val KEY_EMERGENCY_AT = "emergencyAt"
        const val KEY_MIC_MUTED = "micMutedByUs"
        const val KEY_MARKS = "autoAnswerMarks"
        const val MAX_MARKS = 200
    }
}
