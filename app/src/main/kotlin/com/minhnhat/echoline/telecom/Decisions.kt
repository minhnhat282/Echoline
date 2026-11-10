package com.minhnhat.echoline.telecom

import android.os.SystemClock
import android.telecom.Call
import android.telecom.TelecomManager
import com.minhnhat.echoline.domain.model.BlockStyle
import com.minhnhat.echoline.domain.model.Decision
import com.minhnhat.echoline.domain.model.DecisionResult
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

/** Mã ngắn của một quyết định, dùng cho nhật ký quyết định. */
fun decisionCode(d: Decision): String = when (d) {
    Decision.Ring -> "RING"
    is Decision.Block -> if (d.style == BlockStyle.SILENCE) "SILENCE" else "BLOCK"
    is Decision.AutoAnswer -> if (d.delaySec <= 0) "AUTO_HIDDEN" else "AUTO_UI"
}

object Numbers {
    /** Số của cuộc gọi, hoặc null nếu là số ẩn (người gọi chặn hiển thị, hoặc không có số). */
    fun numberOf(details: Call.Details): String? {
        if (details.handlePresentation == TelecomManager.PRESENTATION_RESTRICTED) return null
        val ssp = details.handle?.schemeSpecificPart
        return ssp?.takeIf { it.isNotBlank() }
    }
}

/**
 * Quyết định của bộ lọc cho một cuộc gọi, để InCallService đón lại (không tính lại).
 * @property responded điều đã trả lời cho hệ thống (null nếu bộ lọc chưa kịp trả lời).
 * @property result kết quả đánh giá đầy đủ; có thể hoàn tất muộn hơn [responded] (khi quá giờ canh).
 */
class PendingDecision(val number: String?, val creationTimeMs: Long) {
    val registeredAt: Long = SystemClock.elapsedRealtime()
    val result = CompletableDeferred<DecisionResult>()
    val claimed = AtomicBoolean(false)

    /** Kết quả đánh giá đầy đủ nếu đã có (đọc không chặn, để InCallService hành động ngay). */
    @Volatile var decided: DecisionResult? = null
        private set

    fun complete(r: DecisionResult) {
        decided = r
        result.complete(r)
    }

    /** Khe SIM đã xác định lúc sàng lọc, để InCallService không phải tra lại. */
    @Volatile var simSlot: Int? = null

    /** Thời điểm (elapsedRealtime) bộ lọc trả lời hệ thống. */
    @Volatile var respondedAtElapsed: Long = 0L

    @Volatile var responded: Decision? = null
    @Volatile var source: String = ""
    @Volatile var loggedAtMs: Long = 0L
}

/**
 * Sổ quyết định: khóa là (số, thời điểm tạo cuộc gọi). Mục hết hạn sau 30 giây. Nếu khóa không khớp, chỉ dự phòng
 * theo số khi có ĐÚNG MỘT mục chưa nhận trong ngưỡng 3 giây; nhiều hơn thì không đoán.
 */
class DecisionRegistry {
    private val lock = Any()
    private val entries = ArrayList<PendingDecision>()

    fun register(number: String?, creationTimeMs: Long): PendingDecision {
        val entry = PendingDecision(number, creationTimeMs)
        synchronized(lock) {
            purge()
            entries.add(entry)
        }
        return entry
    }

    fun claim(number: String?, creationTimeMs: Long): PendingDecision? {
        synchronized(lock) {
            purge()
            val now = SystemClock.elapsedRealtime()
            val exact = entries.firstOrNull {
                !it.claimed.get() && it.number == number && it.creationTimeMs == creationTimeMs
            }
            if (exact != null) {
                exact.claimed.set(true)
                return exact
            }
            val near = entries.filter {
                !it.claimed.get() && it.number == number && now - it.registeredAt <= FALLBACK_WINDOW_MS
            }
            if (near.size == 1) {
                near[0].claimed.set(true)
                return near[0]
            }
            return null
        }
    }

    private fun purge() {
        val now = SystemClock.elapsedRealtime()
        entries.removeAll { now - it.registeredAt > EXPIRY_MS }
    }

    private companion object {
        const val EXPIRY_MS = 30_000L
        const val FALLBACK_WINDOW_MS = 3_000L
    }
}
