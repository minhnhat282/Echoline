package com.minhnhat.echoline.telecom

import android.os.Process
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallScreeningService
import com.minhnhat.echoline.App
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.data.DecisionEntry
import com.minhnhat.echoline.diag.Diag
import com.minhnhat.echoline.domain.engine.DecisionEngine
import com.minhnhat.echoline.domain.model.BlockStyle
import com.minhnhat.echoline.domain.model.CallInfo
import com.minhnhat.echoline.domain.model.Decision
import com.minhnhat.echoline.domain.model.DecisionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sàng lọc cuộc gọi đến, chạy TRƯỚC khi cuộc gọi đổ chuông.
 *
 * Đường nhanh: đánh giá ngay ở luồng chính bằng dữ liệu trong bộ nhớ. Nếu có dữ kiện chưa sẵn sàng (vừa khởi động,
 * danh bạ chưa nạp xong) thì bỏ kết quả và đánh giá lại ở luồng nền, có đồng hồ canh [WATCHDOG_MS]: hết giờ thì
 * trả lời "đổ chuông" (không bao giờ để hệ thống chờ quá hạn). Kết quả đầy đủ vẫn được ghi vào [DecisionRegistry]
 * để InCallService áp dụng bù nếu cần.
 */
class EcholineScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val started = SystemClock.elapsedRealtime()
        val gate = AtomicBoolean(false)
        try {
            val graph = (application as App).graph

            if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
                send(callDetails, gate, Decision.Ring)
                return
            }

            val number = Numbers.numberOf(callDetails)
            val simSlot = graph.sims.slotOf(callDetails.accountHandle)
            // Chuẩn hóa số theo vùng của SIM THỰC SỰ nhận cuộc gọi (không phải SIM mặc định).
            val info = CallInfo(number, simSlot, graph.sims.regionOf(callDetails.accountHandle))
            val config = graph.configStore.config.value
            val pending = graph.decisions.register(number, callDetails.creationTimeMillis)
            pending.simSlot = simSlot

            val fastFacts = LiveFacts(graph, excluding = null)
            val quick = DecisionEngine.evaluate(info, config, fastFacts)
            if (!fastFacts.notReady) {
                finish(graph, callDetails, gate, info, pending, quick, "screening-sync", started, completeResult = true)
                return
            }

            // Đường chậm: tính ở luồng nền, canh giờ.
            graph.appScope.launch(Dispatchers.Default) {
                val result = try {
                    evaluateResolving(graph, info, config, excluding = null)
                } catch (e: Exception) {
                    DecisionResult(Decision.Ring, "lỗi khi đánh giá ở đường chậm (${e.message}) -> đổ chuông")
                }
                pending.complete(result)
            }
            graph.appScope.launch(Dispatchers.Default) {
                val result = withTimeoutOrNull(WATCHDOG_MS) { pending.result.await() }
                if (result != null) {
                    finish(graph, callDetails, gate, info, pending, result, "screening-async", started, completeResult = false)
                } else {
                    val timeout = DecisionResult(Decision.Ring, "quá $WATCHDOG_MS ms chưa có kết quả -> đổ chuông")
                    finish(graph, callDetails, gate, info, pending, timeout, "screening-timeout", started, completeResult = false)
                }
            }
        } catch (e: Exception) {
            Diag.e(TAG, "onScreenCall lỗi, đổ chuông bình thường", e)
            send(callDetails, gate, Decision.Ring)
        }
    }

    private fun finish(
        graph: AppGraph,
        details: Call.Details,
        gate: AtomicBoolean,
        info: CallInfo,
        pending: PendingDecision,
        result: DecisionResult,
        source: String,
        startedElapsed: Long,
        completeResult: Boolean
    ) {
        // Ghi điều đã trả lời, và dòng nhật ký quyết định, TRƯỚC khi trả lời hệ thống: InCallService (luồng chính) có thể chạy
        // ngay sau respondToCall, và cần thấy sẵn loggedAtMs cùng dòng nhật ký để ghi chú thêm vào (đặc biệt ở đường chậm,
        // nơi hàm này chạy ở luồng nền).
        pending.responded = result.decision
        pending.source = source
        if (completeResult) pending.complete(result)

        val now = System.currentTimeMillis()
        val beforeSend = SystemClock.elapsedRealtime()
        val elapsed = beforeSend - startedElapsed
        // Tuổi tiến trình: nhỏ nghĩa là cuộc gọi này vừa làm tiến trình khởi động nguội.
        val processAge = beforeSend - Process.getStartElapsedRealtime()
        val coldNote = if (processAge < COLD_START_NOTE_MS) "tiến trình vừa khởi động $processAge ms trước" else null
        pending.loggedAtMs = now
        graph.decisionLog.add(
            DecisionEntry(now, info.number, info.simSlot, decisionCode(result.decision), result.reason, elapsed, source, coldNote)
        )

        val sent = send(details, gate, result.decision)
        // Nếu hệ thống chưa nhận được phản hồi thì KHÔNG được coi là đã xử lý: để InCallService áp dụng bù.
        if (sent == SendResult.FAILED) pending.responded = null
        pending.respondedAtElapsed = SystemClock.elapsedRealtime()

        Diag.i(TAG, "Quyết định ${decisionCode(result.decision)} sau $elapsed ms ($source, tuổi tiến trình $processAge ms): ${result.reason}" + when (sent) {
            SendResult.ALREADY -> " [đã trả lời trước đó]"
            SendResult.FAILED -> " [respondToCall lỗi, để InCallService xử lý bù]"
            SendResult.SENT -> ""
        })
    }

    /** Trả lời hệ thống đúng một lần. */
    private enum class SendResult { SENT, ALREADY, FAILED }

    private fun send(details: Call.Details, gate: AtomicBoolean, decision: Decision): SendResult {
        if (!gate.compareAndSet(false, true)) return SendResult.ALREADY
        val builder = CallResponse.Builder()
        when (decision) {
            Decision.Ring -> Unit
            is Decision.Block ->
                if (decision.style == BlockStyle.SILENCE) {
                    builder.setSilenceCall(true)
                } else {
                    builder.setDisallowCall(true).setRejectCall(true).setSkipCallLog(false).setSkipNotification(true)
                }
            is Decision.AutoAnswer ->
                // Chế độ ẩn: tắt chuông ngay. Chế độ có giao diện: để đổ chuông bình thường.
                if (decision.delaySec <= 0) builder.setSilenceCall(true)
        }
        return try {
            respondToCall(details, builder.build())
            SendResult.SENT
        } catch (e: Exception) {
            Diag.e(TAG, "respondToCall lỗi", e)
            SendResult.FAILED
        }
    }

    private companion object {
        const val TAG = "screening"
        const val WATCHDOG_MS = 1500L
        const val COLD_START_NOTE_MS = 5000L
    }
}
