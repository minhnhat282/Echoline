package com.minhnhat.echoline.telecom

import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.diag.Diag
import com.minhnhat.echoline.domain.model.AutoAnswerSettings
import com.minhnhat.echoline.domain.session.AutoAnswerMachine
import com.minhnhat.echoline.domain.session.CallPhase
import com.minhnhat.echoline.domain.session.RouteKind
import com.minhnhat.echoline.domain.session.SessionEffect
import com.minhnhat.echoline.domain.session.SessionEvent
import com.minhnhat.echoline.domain.session.Snapshot
import com.minhnhat.echoline.domain.session.TimerKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Adapter Android cho [AutoAnswerMachine] (toàn bộ LOGIC quyết định nằm ở máy trạng thái trong module domain, có test JVM).
 * Lớp này chỉ: dịch sự kiện Android thành [SessionEvent], và thực hiện các [SessionEffect] bằng Call/InCallService/hẹn giờ.
 *
 * Mọi hàm chạy ở luồng chính (cùng luồng với callback của InCallService).
 */
class AutoAnswerRunner(
    private val scope: CoroutineScope,
    private val service: InCallService,
    private val graph: AppGraph,
    private val onUiChanged: () -> Unit
) {
    private class Session(
        val call: Call,
        val entry: CallEntry,
        val machine: AutoAnswerMachine,
        val hidden: Boolean,
        val settings: AutoAnswerSettings
    ) {
        val timers = HashMap<TimerKind, Job>()
        var callback: Call.Callback? = null
        var routeJob: Job? = null
        var monitorJob: Job? = null
        var finished = false
        var micChecked = false
        var micChanged = false
        var answerSentAt = 0L
    }

    private val sessions = HashMap<Call, Session>()

    fun start(entry: CallEntry, delaySec: Int) {
        val call = entry.call
        if (sessions.containsKey(call)) return
        val settings = graph.configStore.config.value.settings.autoAnswer
        val machine = AutoAnswerMachine(entry.hidden, delaySec, settings)
        val session = Session(call, entry, machine, entry.hidden, settings)
        sessions[call] = session

        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                dispatch(session, SessionEvent.CallPhaseChanged(phaseOf(state)))
            }
        }
        session.callback = callback
        call.registerCallback(callback)

        Diag.i(TAG, "Bắt đầu phiên tự trả lời: ẩn=${session.hidden}, chờ ${delaySec}s")
        // Gọi đồng bộ: với chế độ ẩn, lệnh nghe máy được gửi ngay trong lần gọi này, không qua hàng đợi.
        dispatch(session, SessionEvent.Begin(snapshot(session)))
    }

    fun onNewCall(newCall: Call) {
        for (s in sessions.values.toList()) {
            if (s.call !== newCall) dispatch(s, SessionEvent.OtherCallArrived(phaseOf(stateOf(s.call))))
        }
    }

    fun onCallRemoved(call: Call) {
        sessions[call]?.let { dispatch(it, SessionEvent.CallRemoved) }
    }

    fun shutdown() {
        for (s in sessions.values.toList()) dispatch(s, SessionEvent.Shutdown)
    }

    // ------------------------------------------------------------------ điều phối

    private fun dispatch(s: Session, event: SessionEvent) {
        if (s.finished) return
        val effects = try {
            s.machine.handle(event)
        } catch (e: Exception) {
            Diag.e(TAG, "Máy trạng thái lỗi với sự kiện $event", e)
            forceEnd(s)
            return
        }
        for (effect in effects) {
            if (s.finished) break
            try {
                execute(s, effect)
            } catch (e: Exception) {
                Diag.e(TAG, "Thực hiện $effect lỗi", e)
            }
        }
    }

    private fun execute(s: Session, effect: SessionEffect) {
        when (effect) {
            is SessionEffect.StartTimer -> {
                s.timers.remove(effect.kind)?.cancel()
                s.timers[effect.kind] = scope.launch {
                    delay(effect.millis)
                    onTimer(s, effect.kind)
                }
            }
            is SessionEffect.CancelTimer -> s.timers.remove(effect.kind)?.cancel()
            SessionEffect.AnswerCall -> answer(s)
            SessionEffect.ApplyMute -> muteIfNeeded(s)
            SessionEffect.RestoreMute -> restoreMicIfWeChanged(s)
            SessionEffect.EnsurePrivateRoute -> ensureRoute(s)
            SessionEffect.MarkAnswered -> markAnswered(s)
            is SessionEffect.Disconnect -> {
                Diag.i(TAG, "Cúp cuộc tự trả lời: ${effect.reason}")
                s.call.disconnect()
            }
            is SessionEffect.GiveUp -> giveUp(s, effect)
            is SessionEffect.Note -> note(s, effect.text)
            is SessionEffect.Finish -> finish(s, effect.reason)
        }
    }

    private fun onTimer(s: Session, kind: TimerKind) {
        when (kind) {
            TimerKind.DELAY -> dispatch(s, SessionEvent.DelayElapsed(snapshot(s)))
            TimerKind.ANSWER_TIMEOUT -> dispatch(s, SessionEvent.AnswerTimeout(phaseOf(stateOf(s.call))))
            TimerKind.HANG_UP -> dispatch(s, SessionEvent.HangUpElapsed)
        }
    }

    // ------------------------------------------------------------------ các việc cụ thể

    private fun answer(s: Session) {
        s.answerSentAt = SystemClock.elapsedRealtime()
        try {
            s.call.answer(VideoProfile.STATE_AUDIO_ONLY)
        } catch (e: Exception) {
            Diag.e(TAG, "call.answer() lỗi", e)
            dispatch(s, SessionEvent.AnswerFailed(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun markAnswered(s: Session) {
        val now = SystemClock.elapsedRealtime()
        graph.prefs.addAutoAnswerMark(s.call.details.creationTimeMillis)
        val toAnswer = s.answerSentAt - s.entry.addedAt
        val toActive = now - s.answerSentAt
        val line = "tốc độ: nhận cuộc gọi → lệnh nghe $toAnswer ms, lệnh nghe → ACTIVE $toActive ms"
        Diag.i(TAG, line)
        graph.decisionLog.annotate(s.entry.logTimeMs, line)
    }

    private fun note(s: Session, text: String) {
        Diag.d(TAG, "Ghi chú: $text")
        graph.decisionLog.annotate(s.entry.logTimeMs, text)
    }

    private fun giveUp(s: Session, effect: SessionEffect.GiveUp) {
        Diag.w(TAG, "Không tự trả lời: ${effect.reason}")
        val state = stateOf(s.call)
        val ringing = state == Call.STATE_RINGING || state == Call.STATE_SIMULATED_RINGING
        if (effect.restoreRinging) {
            // Hệ thống đã tắt chuông: hiện giao diện (và phát chuông thay thế nếu còn đang đổ chuông)
            // để cuộc gọi không hiện ra trong im lặng. Bỏ ẩn cả khi lệnh nghe đã kịp có hiệu lực.
            s.entry.hidden = false
            graph.callRegistry.publish()
            if (ringing) graph.ringer.start()
            onUiChanged()
        }
    }

    private fun finish(s: Session, reason: String) {
        if (s.finished) return
        s.finished = true
        Diag.d(TAG, "Kết thúc phiên: $reason")
        // Micro do phiên này tắt thì phiên này trả lại ngay khi kết thúc (mỗi lúc chỉ có tối đa một phiên tự trả lời), không chờ
        // "hết mọi cuộc gọi": nếu có cuộc khác đang tới/đang gọi thì micro của nó không được bị tắt nhầm.
        restoreMicIfWeChanged(s)
        for (job in s.timers.values) job.cancel()
        s.timers.clear()
        s.routeJob?.cancel()
        s.monitorJob?.cancel()
        s.callback?.let {
            try {
                s.call.unregisterCallback(it)
            } catch (e: Exception) {
                Diag.w(TAG, "Không gỡ callback: ${e.message}")
            }
        }
        sessions.remove(s.call)
    }

    private fun forceEnd(s: Session) {
        if (s.micChanged) restoreMicIfWeChanged(s)
        finish(s, "lỗi nội bộ")
    }

    // ------------------------------------------------------------------ âm thanh

    /**
     * Tắt micro. Chỉ đánh dấu "do ứng dụng tắt" khi micro không được biết là đã tắt từ trước. Khi chưa đọc được trạng thái âm thanh
     * (null) thì coi như micro đang bật: lúc cuộc gọi tự trả lời bắt đầu (không có cuộc khác) micro luôn ở trạng thái mặc định.
     */
    @Suppress("DEPRECATION")
    private fun muteIfNeeded(s: Session) {
        if (!s.settings.muteMic) return
        if (!s.micChecked) {
            s.micChecked = true
            val alreadyMuted = service.callAudioState?.isMuted == true
            if (!alreadyMuted) {
                graph.prefs.micMutedByUs = true
                s.micChanged = true
            }
        }
        try {
            service.setMuted(true)
        } catch (e: Exception) {
            Diag.e(TAG, "setMuted(true) lỗi", e)
            // Không tắt được thì không được giữ cờ "do ứng dụng tắt", và phải cho lần thử sau kiểm tra lại quyền sở hữu từ đầu
            // (nếu không, lần thử sau thành công sẽ không được ghi nhận là do ứng dụng tắt và sẽ không được khôi phục).
            if (s.micChanged) {
                graph.prefs.micMutedByUs = false
                s.micChanged = false
            }
            s.micChecked = false
        }
    }

    /**
     * Trả lại micro nếu chính ứng dụng đã tắt. Chỉ xóa cờ khi lệnh thành công: nếu thất bại, cờ [AppPrefs.micMutedByUs] được giữ để
     * đường dự phòng ([AudioProbe.restoreMicIfNeeded], khi không còn cuộc gọi hoặc khi khởi động lại) khôi phục sau.
     */
    private fun restoreMicIfWeChanged(s: Session) {
        if (!s.micChanged) return
        try {
            service.setMuted(false)
            graph.prefs.micMutedByUs = false
            s.micChanged = false
        } catch (e: Exception) {
            Diag.w(TAG, "Không trả lại micro (giữ cờ để khôi phục sau): ${e.message}")
        }
    }

    private fun ensureRoute(s: Session) {
        s.routeJob = scope.launch {
            // Mọi kết quả (kể cả ngoại lệ) đều phải đến được máy trạng thái, nếu không phiên sẽ kẹt không ai giám sát.
            val ok = try {
                ensurePrivateRoute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.e(TAG, "Xác minh route âm thanh lỗi", e)
                false
            }
            dispatch(s, SessionEvent.RouteEnsured(ok))
            if (ok && !s.finished) startAudioMonitor(s)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun ensurePrivateRoute(): Boolean {
        val target = if (graph.audio.hasWiredHeadset()) CallAudioState.ROUTE_WIRED_HEADSET else CallAudioState.ROUTE_EARPIECE
        repeat(ROUTE_RETRIES) {
            val state = service.callAudioState
            if (state != null && isPrivate(state.route)) return true
            service.setAudioRoute(target)
            // Chờ hệ thống báo đã đổi route (phản ứng ngay khi báo) thay vì ngủ cố định; hết hạn thì thử lại. drop(1) bỏ giá trị hiện
            // có (có thể là của cuộc gọi trước), chỉ nhận thay đổi mới. Kết quả luôn được kiểm lại bằng trạng thái thật ở đầu vòng sau.
            val changed = withTimeoutOrNull(ROUTE_RETRY_MS) {
                graph.callRegistry.audio.drop(1).first { isPrivate(it.route) }
            }
            if (changed != null) {
                val now = service.callAudioState
                if (now != null && isPrivate(now.route)) return true
            }
        }
        val state = service.callAudioState
        return state != null && isPrivate(state.route)
    }

    private fun startAudioMonitor(s: Session) {
        s.monitorJob = scope.launch {
            graph.callRegistry.audio.collect { audio ->
                dispatch(s, SessionEvent.AudioChanged(routeKindOf(audio.route), audio.muted))
            }
        }
    }

    // ------------------------------------------------------------------ chuyển đổi Android <-> domain

    private fun snapshot(s: Session): Snapshot = Snapshot(
        callPhase = phaseOf(stateOf(s.call)),
        otherLiveCalls = graph.callRegistry.liveCount(excluding = s.call),
        hasPrivateRoute = graph.audio.hasPrivateRoute()
    )

    private fun phaseOf(state: Int): CallPhase = when (state) {
        Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> CallPhase.RINGING
        Call.STATE_ACTIVE -> CallPhase.ACTIVE
        Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> CallPhase.ENDED
        else -> CallPhase.OTHER
    }

    private fun isPrivate(route: Int): Boolean =
        route == CallAudioState.ROUTE_EARPIECE || route == CallAudioState.ROUTE_WIRED_HEADSET

    private fun routeKindOf(route: Int): RouteKind = when {
        route == 0 -> RouteKind.UNKNOWN
        isPrivate(route) -> RouteKind.PRIVATE
        else -> RouteKind.OTHER
    }

    private companion object {
        const val TAG = "autoanswer"
        const val ROUTE_RETRIES = 6
        const val ROUTE_RETRY_MS = 300L
    }
}
