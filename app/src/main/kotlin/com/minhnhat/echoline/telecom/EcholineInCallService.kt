package com.minhnhat.echoline.telecom

import android.content.Intent
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import com.minhnhat.echoline.App
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.data.DecisionEntry
import com.minhnhat.echoline.data.systemService
import com.minhnhat.echoline.diag.Diag
import com.minhnhat.echoline.domain.model.BlockStyle
import com.minhnhat.echoline.domain.model.CallInfo
import com.minhnhat.echoline.domain.model.Decision
import com.minhnhat.echoline.domain.model.DecisionResult
import com.minhnhat.echoline.ui.incall.InCallActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Giao diện cuộc gọi của ứng dụng điện thoại mặc định. Nhận mọi cuộc gọi, đón quyết định của bộ lọc
 * (hoặc tự đánh giá nếu bộ lọc không chạy), áp dụng bù nếu cần, và điều khiển tự trả lời.
 *
 * Nguyên tắc về tốc độ: với cuộc gọi cần hành động Telecom (nghe/từ chối), lệnh đó được gửi TRƯỚC mọi việc chỉ phục vụ giao diện
 * (tra tên danh bạ, nhãn SIM, thông báo).
 */
class EcholineInCallService : InCallService() {

    private val graph: AppGraph get() = (application as App).graph
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, t -> Diag.e(TAG, "Lỗi coroutine trong InCallService", t) }
    )
    private lateinit var runner: AutoAnswerRunner

    override fun onCreate() {
        super.onCreate()
        graph.callRegistry.service = this
        runner = AutoAnswerRunner(scope, this, graph) { refreshUi() }
        Diag.i(TAG, "InCallService khởi tạo")
    }

    override fun onDestroy() {
        Diag.i(TAG, "InCallService dừng")
        runner.shutdown()
        graph.ringer.stop()
        graph.callRegistry.service = null
        graph.audio.restoreMicIfNeeded()
        scope.cancel()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        try {
            val details = call.details
            val incoming = details.callDirection == Call.Details.DIRECTION_INCOMING
            val entry = CallEntry(call, incoming)
            entry.number = Numbers.numberOf(details)

            // 1) Nhận quyết định của bộ lọc (nếu có) trước tiên; đã có sẵn khe SIM thì không tra lại.
            val pending = if (incoming) graph.decisions.claim(entry.number, details.creationTimeMillis) else null
            entry.simSlot = pending?.simSlot ?: graph.sims.slotOf(details.accountHandle)
            if (pending != null && pending.respondedAtElapsed > 0L) {
                Diag.i(TAG, "onCallAdded sau ${entry.addedAt - pending.respondedAtElapsed} ms kể từ khi sàng lọc trả lời")
            }

            // 2) Đăng ký tối thiểu rồi hành động ngay (nghe/từ chối). Chưa làm việc gì chỉ phục vụ giao diện.
            graph.callRegistry.add(entry)
            val callback = object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) {
                    graph.callRegistry.publish()
                    refreshUi()
                }

                override fun onDetailsChanged(call: Call, details: Call.Details) {
                    graph.callRegistry.publish()
                }
            }
            entry.callback = callback
            call.registerCallback(callback)

            runner.onNewCall(call)
            if (incoming) handleIncoming(entry, pending) else handleOutgoing(entry)

            // 3) Việc phụ, SAU hành động Telecom: nhãn SIM, tên người gọi, thông báo.
            entry.simLabel = if (graph.sims.activeSims().size >= 2) graph.sims.labelOf(entry.simSlot) else null
            lookupName(entry)
            graph.callRegistry.publish()
            refreshUi()
        } catch (e: Exception) {
            Diag.e(TAG, "onCallAdded lỗi", e)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        try {
            runner.onCallRemoved(call)
            val entry = graph.callRegistry.remove(call)
            if (entry != null) {
                graph.notifications.cancel(entry.id)
                entry.callback?.let { call.unregisterCallback(it) }
            }
            if (graph.callRegistry.liveCount(null) == 0) {
                graph.ringer.stop()
                graph.audio.restoreMicIfNeeded()
            }
            refreshUi()
        } catch (e: Exception) {
            Diag.e(TAG, "onCallRemoved lỗi", e)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        graph.callRegistry.setAudio(audioState)
    }

    // ------------------------------------------------------------------ cuộc gọi đi

    private fun handleOutgoing(entry: CallEntry) {
        val number = entry.number
        if (number != null && graph.emergency.isEmergencyNumber(number)) {
            graph.emergency.markEmergencyCall()
            Diag.i(TAG, "Ghi nhận cuộc gọi khẩn cấp: ân hạn không chặn đã bắt đầu")
        }
        openCallUi()
    }

    private fun openCallUi() {
        try {
            startActivity(
                Intent(this, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            Diag.w(TAG, "Không mở được giao diện cuộc gọi: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ cuộc gọi đến

    private fun handleIncoming(entry: CallEntry, pending: PendingDecision?) {
        val responded = pending?.responded
        entry.hidden = responded is Decision.AutoAnswer && responded.delaySec <= 0
        entry.logTimeMs = pending?.loggedAtMs ?: 0L

        // Đường tức thời: bộ lọc đã có kết quả đầy đủ, áp dụng ngay trong lần gọi này (không qua coroutine).
        val ready = pending?.decided
        if (ready != null) {
            applyDecision(entry, responded, ready)
            return
        }

        scope.launch {
            val started = SystemClock.elapsedRealtime()
            val result: DecisionResult
            if (pending != null) {
                // Bộ lọc đã quá giờ canh (đã trả lời "đổ chuông") nhưng vẫn đang tính: chờ kết quả thật để áp dụng bù.
                result = withTimeoutOrNull(WAIT_RESULT_MS) { pending.result.await() }
                    ?: DecisionResult(Decision.Ring, "quá hạn chờ kết quả của bộ lọc -> đổ chuông")
            } else {
                // Bộ lọc chưa/không chạy cho cuộc gọi này: tự đánh giá.
                result = withContext(Dispatchers.Default) { evaluateFallback(entry) }
                val now = System.currentTimeMillis()
                entry.logTimeMs = now
                graph.decisionLog.add(
                    DecisionEntry(
                        now, entry.number, entry.simSlot, decisionCode(result.decision), result.reason,
                        SystemClock.elapsedRealtime() - started, "incall-fallback"
                    )
                )
                Diag.w(TAG, "Bộ lọc không chạy cho cuộc gọi này; đã tự đánh giá: ${decisionCode(result.decision)}")
            }
            applyDecision(entry, responded, result)
            graph.callRegistry.publish()
            refreshUi()
        }
    }

    private suspend fun evaluateFallback(entry: CallEntry): DecisionResult {
        return try {
            val region = graph.sims.regionOf(entry.call.details.accountHandle)
            val info = CallInfo(entry.number, entry.simSlot, region)
            evaluateResolving(graph, info, graph.configStore.config.value, entry.call)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DecisionResult(Decision.Ring, "lỗi khi tự đánh giá (${e.message}) -> đổ chuông")
        }
    }

    /** Áp dụng kết quả. [responded] là điều đã báo cho hệ thống (null nếu bộ lọc không chạy hoặc trả lời thất bại). */
    private fun applyDecision(entry: CallEntry, responded: Decision?, result: DecisionResult) {
        val call = entry.call
        if (!isLiveState(stateOf(call))) return
        when (val d = result.decision) {
            is Decision.Block -> {
                if (responded is Decision.Block) {
                    // Hệ thống đã xử lý. Kiểu "tắt chuông": cuộc gọi vẫn hiện, không đổ chuông.
                    entry.hidden = false
                } else if (!isRingingState(stateOf(call))) {
                    // Kết quả đến muộn, lúc này cuộc gọi đã được người dùng nghe (hoặc đã chuyển trạng thái): tôn trọng thao tác
                    // của người dùng, KHÔNG từ chối cuộc gọi đã nghe.
                    Diag.w(TAG, "Quyết định chặn đến muộn, cuộc gọi không còn đổ chuông (trạng thái ${stateOf(call)}): bỏ qua")
                    graph.decisionLog.annotate(entry.logTimeMs, "quyết định chặn đến muộn, cuộc gọi không còn đổ chuông: không áp dụng")
                    entry.hidden = false
                } else {
                    // Kết quả đến muộn hoặc bộ lọc không chạy: áp dụng bù.
                    Diag.w(TAG, "Áp dụng bù quyết định chặn (${if (d.style == BlockStyle.SILENCE) "tắt chuông" else "từ chối"})")
                    graph.decisionLog.annotate(entry.logTimeMs, "áp dụng bù: ${if (d.style == BlockStyle.SILENCE) "tắt chuông" else "từ chối"}")
                    if (d.style == BlockStyle.SILENCE) {
                        entry.hidden = false
                        silenceRinger(entry)
                    } else {
                        entry.hidden = true
                        try {
                            call.reject(false, null)
                        } catch (e: Exception) {
                            Diag.e(TAG, "Không từ chối được cuộc gọi", e)
                            entry.hidden = false
                        }
                    }
                }
            }
            is Decision.AutoAnswer -> {
                val systemSilenced = responded is Decision.AutoAnswer && responded.delaySec <= 0
                entry.hidden = d.delaySec <= 0 && systemSilenced
                runner.start(entry, d.delaySec)
            }
            Decision.Ring -> entry.hidden = false
        }
    }

    /**
     * Tắt chuông (áp dụng bù cho kiểu "tắt chuông" khi kết quả sàng lọc đến muộn). [TelecomManager.silenceRinger] cho phép ứng dụng
     * điện thoại mặc định gọi mà không cần quyền riêng. Dù vậy mọi thất bại đều được ghi vào nhật ký quyết định (không chỉ Logcat),
     * để thấy được trên máy thật nếu một nhà sản xuất chặn.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun silenceRinger(entry: CallEntry) {
        try {
            systemService<TelecomManager>().silenceRinger()
            Diag.i(TAG, "Đã tắt chuông (áp dụng bù)")
        } catch (e: Exception) {
            Diag.w(TAG, "Không tắt được chuông: ${e.message}")
            graph.decisionLog.annotate(entry.logTimeMs, "tắt chuông thất bại: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ giao diện / thông báo

    private fun lookupName(entry: CallEntry) {
        val number = entry.number ?: return
        scope.launch {
            val name = withContext(Dispatchers.IO) { graph.contacts.displayName(number) }
            if (name != null) {
                entry.name = name
                graph.callRegistry.publish()
                refreshUi()
            }
        }
    }

    /** Đồng bộ thông báo với trạng thái các cuộc gọi hiện có (cuộc ẩn thì không có thông báo). */
    private fun refreshUi() {
        try {
            var anyRinging = false
            for (ui in graph.callRegistry.snapshot()) {
                when {
                    ui.hidden -> graph.notifications.cancel(ui.id)
                    ui.incoming && isRingingState(ui.state) -> {
                        graph.notifications.postIncoming(ui)
                        anyRinging = true
                    }
                    !isLiveState(ui.state) -> graph.notifications.cancel(ui.id)
                    else -> graph.notifications.postOngoing(ui)
                }
            }
            if (!anyRinging) graph.ringer.stop()
        } catch (e: Exception) {
            Diag.e(TAG, "refreshUi lỗi", e)
        }
    }

    private companion object {
        const val TAG = "incall"
        const val WAIT_RESULT_MS = 8000L
    }
}
