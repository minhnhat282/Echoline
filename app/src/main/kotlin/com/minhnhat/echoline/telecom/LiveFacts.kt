package com.minhnhat.echoline.telecom

import android.telecom.Call
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.diag.Diag
import com.minhnhat.echoline.domain.engine.ContactsUnavailableException
import com.minhnhat.echoline.domain.engine.DecisionEngine
import com.minhnhat.echoline.domain.engine.Facts
import com.minhnhat.echoline.domain.model.CallInfo
import com.minhnhat.echoline.domain.model.Decision
import com.minhnhat.echoline.domain.model.DecisionResult
import com.minhnhat.echoline.domain.model.FilterConfig
import com.minhnhat.echoline.domain.number.NumberKeys
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Dữ kiện thật cho engine, đọc từ bộ nhớ đệm. KHÔNG BAO GIỜ chặn luồng.
 *
 * Nguyên tắc: một câu trả lời "có" từ bản chụp luôn tin được; một câu trả lời "không" chỉ tin được khi chỉ mục đã được làm
 * mới từ hệ thống trong tiến trình này (bản chụp nạp từ đĩa lúc khởi động có thể cũ). Khi chưa chắc, [notReady] = true và
 * dữ kiện đang cần được ghi lại: nơi gọi phải bỏ kết quả, gọi [resolvePending] (suspend, có thể chờ I/O) rồi đánh giá lại.
 * Hàm [evaluateResolving] làm đúng việc đó cho đường chậm.
 *
 * Mỗi lần chỉ ghi lại MỘT dữ kiện đang cần (cái đầu tiên engine hỏi), để không truy vấn thừa: sau khi có câu trả lời, engine
 * có thể không cần hỏi dữ kiện thứ hai.
 *
 * @param excluding cuộc gọi không tính là "cuộc khác" (chính cuộc đang xét).
 */
class LiveFacts(
    private val graph: AppGraph,
    private val excluding: Call?
) : Facts {

    var notReady: Boolean = false
        private set

    private sealed interface Need {
        class Contact(val keys: NumberKeys) : Need
        class Recent(val keys: NumberKeys, val windowMs: Long) : Need
    }

    private var need: Need? = null

    // Kết quả đã xác minh trong lần đánh giá này. null (với danh bạ) nghĩa là không xác minh được.
    private val contactAnswers = HashMap<String, Boolean?>()
    private val recentAnswers = HashMap<String, Boolean>()

    private fun markNeed(n: Need) {
        notReady = true
        if (need == null) need = n
    }

    override fun isContact(keys: NumberKeys): Boolean {
        val contacts = graph.contacts
        if (!contacts.hasPermission()) throw ContactsUnavailableException("chưa cấp quyền Danh bạ")
        if (contactAnswers.containsKey(keys.exactKey)) {
            return contactAnswers[keys.exactKey] ?: throw ContactsUnavailableException("không xác minh được danh bạ")
        }
        contacts.containsTrusted(keys)?.let { return it }
        markNeed(Need.Contact(keys))
        return false
    }

    override fun hasOtherLiveCall(): Boolean = graph.callRegistry.liveCount(excluding) > 0

    override fun hasPrivateRoute(): Boolean = graph.audio.hasPrivateRoute()

    /** Không biết lịch sử thì coi như "đã có" (an toàn: không tự trả lời nhầm). */
    override fun hasRecentHistory(keys: NumberKeys, windowMs: Long): Boolean {
        val recents = graph.recents
        if (!recents.hasPermission()) {
            Diag.w(TAG, "Chưa có quyền Nhật ký cuộc gọi: coi như số đã có trong lịch sử")
            return true
        }
        val cacheKey = "${keys.exactKey}|$windowMs"
        recentAnswers[cacheKey]?.let { return it }
        recents.hasRecentTrusted(keys, windowMs)?.let { return it }
        markNeed(Need.Recent(keys, windowMs))
        return true
    }

    override fun emergencyGraceActive(): Boolean =
        graph.emergency.graceActive(graph.configStore.config.value.settings.emergencyGraceDays)

    /**
     * Xác minh dữ kiện đang cần (nếu có) rồi xóa cờ [notReady]. Có thể chờ I/O nhưng không chặn luồng.
     * @return false nếu không có gì cần xác minh.
     */
    suspend fun resolvePending(): Boolean {
        val n = need ?: return false
        need = null
        notReady = false
        when (n) {
            is Need.Contact -> contactAnswers[n.keys.exactKey] = graph.contacts.resolve(n.keys)
            is Need.Recent -> {
                val answer = graph.recents.resolve(n.keys, n.windowMs)
                if (answer == null) Diag.w(TAG, "Không xác minh được lịch sử cuộc gọi: coi như số đã có trong lịch sử")
                recentAnswers["${n.keys.exactKey}|${n.windowMs}"] = answer ?: true
            }
        }
        return true
    }

    private companion object {
        const val TAG = "facts"
    }
}

private const val RESOLVE_TOTAL_MS = 1200L
private const val RESOLVE_MAX_ROUNDS = 4

/**
 * Đánh giá ở đường chậm: đánh giá, nếu còn dữ kiện chưa chắc thì xác minh rồi đánh giá lại. Có một hạn chót chung
 * ([RESOLVE_TOTAL_MS]); hết hạn hoặc không xác minh được thì "đổ chuông" (an toàn). Mọi lần chờ đều là suspend, không chặn luồng.
 */
suspend fun evaluateResolving(graph: AppGraph, info: CallInfo, config: FilterConfig, excluding: Call?): DecisionResult {
    val facts = LiveFacts(graph, excluding)
    val result = withTimeoutOrNull(RESOLVE_TOTAL_MS) {
        var r = DecisionEngine.evaluate(info, config, facts)
        var rounds = 0
        while (facts.notReady && rounds < RESOLVE_MAX_ROUNDS) {
            rounds++
            if (!facts.resolvePending()) break
            r = DecisionEngine.evaluate(info, config, facts)
        }
        if (facts.notReady) DecisionResult(Decision.Ring, "dữ kiện chưa xác minh được -> đổ chuông") else r
    }
    return result ?: DecisionResult(Decision.Ring, "quá $RESOLVE_TOTAL_MS ms chờ dữ kiện -> đổ chuông")
}
