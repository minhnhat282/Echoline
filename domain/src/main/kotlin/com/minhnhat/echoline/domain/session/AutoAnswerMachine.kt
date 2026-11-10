package com.minhnhat.echoline.domain.session

import com.minhnhat.echoline.domain.model.AutoAnswerSettings

/** Trạng thái cuộc gọi, đã được tầng Android quy về 4 giá trị. */
enum class CallPhase { RINGING, ACTIVE, ENDED, OTHER }

/** Loại đường âm thanh hiện tại: chưa biết, riêng tư (loa trong hoặc tai nghe có dây), hoặc loại khác (loa ngoài, Bluetooth). */
enum class RouteKind { UNKNOWN, PRIVATE, OTHER }

enum class TimerKind { DELAY, ANSWER_TIMEOUT, HANG_UP }

/** Các dữ kiện cần để quyết định có nên nghe máy hay không, do tầng Android đọc tại thời điểm quyết định. */
data class Snapshot(val callPhase: CallPhase, val otherLiveCalls: Int, val hasPrivateRoute: Boolean)

/** Sự kiện đi VÀO máy trạng thái. */
sealed interface SessionEvent {
    data class Begin(val snapshot: Snapshot) : SessionEvent
    data class DelayElapsed(val snapshot: Snapshot) : SessionEvent
    data class CallPhaseChanged(val phase: CallPhase) : SessionEvent
    data class AnswerFailed(val message: String) : SessionEvent
    data class AnswerTimeout(val phase: CallPhase) : SessionEvent
    data class RouteEnsured(val ok: Boolean) : SessionEvent
    data class AudioChanged(val route: RouteKind, val muted: Boolean) : SessionEvent
    data object HangUpElapsed : SessionEvent
    /** Có cuộc gọi khác xuất hiện; [phase] là trạng thái hiện tại của CHÍNH cuộc đang được tự trả lời. */
    data class OtherCallArrived(val phase: CallPhase) : SessionEvent
    data object CallRemoved : SessionEvent
    data object Shutdown : SessionEvent
}

/** Việc máy trạng thái yêu cầu tầng Android thực hiện (theo đúng thứ tự trong danh sách trả về). */
sealed interface SessionEffect {
    data class StartTimer(val kind: TimerKind, val millis: Long) : SessionEffect
    data class CancelTimer(val kind: TimerKind) : SessionEffect
    data object AnswerCall : SessionEffect
    data object ApplyMute : SessionEffect
    data object RestoreMute : SessionEffect
    data object EnsurePrivateRoute : SessionEffect
    data object MarkAnswered : SessionEffect
    data class Disconnect(val reason: String) : SessionEffect

    /** Không tự trả lời được; [restoreRinging]: cuộc gọi đã bị tắt chuông nên phải hiện giao diện và phát chuông thay thế. */
    data class GiveUp(val reason: String, val restoreRinging: Boolean) : SessionEffect

    data class Note(val text: String) : SessionEffect
    data class Finish(val reason: String) : SessionEffect
}

/**
 * Máy trạng thái của MỘT phiên tự trả lời. Kotlin thuần, không biết gì về Android: nhận sự kiện, trả về danh sách việc cần làm.
 *
 * Quy tắc bất biến:
 *  - Cuộc gọi chưa nghe được (kể cả đã gửi lệnh nghe mà vẫn đang đổ chuông) thì không bao giờ bị cúp: quay về đổ chuông ([SessionEffect.GiveUp]).
 *  - Cuộc đã nghe mà không đảm bảo được âm thanh riêng tư thì bị cúp.
 *  - Sau [Phase.FINISHED] mọi sự kiện đều bị bỏ qua.
 *  - Micro: chỉ yêu cầu trả lại ([SessionEffect.RestoreMute]) khi phiên kết thúc trước khi cuộc gọi ACTIVE; sau khi ACTIVE,
 *    việc trả micro do tầng Android làm lúc cuộc gọi kết thúc.
 *
 * @param hidden hệ thống đã tắt chuông, không có giao diện (chế độ chờ 0 giây).
 * @param delaySec chỉ có nghĩa khi không [hidden]: đổ chuông bình thường rồi chờ chừng này giây mới nghe.
 */
class AutoAnswerMachine(
    private val hidden: Boolean,
    private val delaySec: Int,
    private val settings: AutoAnswerSettings
) {
    enum class Phase { NEW, DELAYING, ANSWERING, ACTIVE, FINISHED }

    var phase: Phase = Phase.NEW
        private set

    private var muteRequested = false
    private var monitoring = false
    private var hangTimerRunning = false
    private var sawMuted = false

    fun handle(event: SessionEvent): List<SessionEffect> {
        if (phase == Phase.FINISHED) return emptyList()
        return when (event) {
            is SessionEvent.Begin -> onBegin(event.snapshot)
            is SessionEvent.DelayElapsed -> if (phase == Phase.DELAYING) evaluate(event.snapshot) else emptyList()
            is SessionEvent.CallPhaseChanged -> onCallPhase(event.phase)
            is SessionEvent.AnswerFailed -> onAnswerFailed(event.message)
            is SessionEvent.AnswerTimeout -> onAnswerTimeout(event.phase)
            is SessionEvent.RouteEnsured -> onRouteEnsured(event.ok)
            is SessionEvent.AudioChanged -> onAudio(event.route, event.muted)
            SessionEvent.HangUpElapsed ->
                if (phase == Phase.ACTIVE) disconnect("hết ${settings.hangUpAfterSec} giây") else emptyList()
            is SessionEvent.OtherCallArrived -> onOtherCall(event.phase)
            SessionEvent.CallRemoved -> finish("cuộc gọi đã kết thúc")
            SessionEvent.Shutdown -> finish("dịch vụ dừng")
        }
    }

    private fun onBegin(s: Snapshot): List<SessionEffect> {
        if (phase != Phase.NEW) return emptyList()
        if (!hidden && delaySec > 0) {
            phase = Phase.DELAYING
            return listOf(SessionEffect.StartTimer(TimerKind.DELAY, delaySec * 1000L))
        }
        return evaluate(s)
    }

    /** Điểm quyết định duy nhất: nghe hay không. */
    private fun evaluate(s: Snapshot): List<SessionEffect> {
        if (s.callPhase != CallPhase.RINGING) return finish("không còn đổ chuông")
        if (s.otherLiveCalls > 0) return giveUp("đang có cuộc gọi khác")
        if (!s.hasPrivateRoute) return giveUp("không có đường âm thanh riêng tư (đang dùng Bluetooth hoặc không có loa trong/tai nghe có dây)")

        phase = Phase.ANSWERING
        val out = ArrayList<SessionEffect>()
        out += SessionEffect.AnswerCall
        if (settings.muteMic) {
            muteRequested = true
            out += SessionEffect.ApplyMute
        }
        out += SessionEffect.StartTimer(TimerKind.ANSWER_TIMEOUT, ANSWER_TIMEOUT_MS)
        return out
    }

    private fun onCallPhase(p: CallPhase): List<SessionEffect> = when (phase) {
        Phase.DELAYING ->
            // Người dùng tự trả lời/từ chối, hoặc người gọi cúp trong lúc chờ: không can thiệp nữa.
            if (p != CallPhase.RINGING) finish("cuộc gọi không còn đổ chuông") else emptyList()
        Phase.ANSWERING -> when (p) {
            CallPhase.ACTIVE -> becomeActive()
            CallPhase.ENDED -> finish("cuộc gọi đã kết thúc trong lúc chờ nghe")
            else -> emptyList()
        }
        Phase.ACTIVE -> if (p == CallPhase.ENDED) finish("cuộc gọi đã kết thúc") else emptyList()
        else -> emptyList()
    }

    private fun becomeActive(): List<SessionEffect> {
        phase = Phase.ACTIVE
        val out = ArrayList<SessionEffect>()
        out += SessionEffect.CancelTimer(TimerKind.ANSWER_TIMEOUT)
        out += SessionEffect.MarkAnswered
        out += SessionEffect.Note("đã tự trả lời")
        if (settings.muteMic) out += SessionEffect.ApplyMute // nhắc lại cho chắc, phòng lệnh trước đó bị bỏ lỡ
        out += SessionEffect.EnsurePrivateRoute
        return out
    }

    private fun onAnswerFailed(message: String): List<SessionEffect> =
        if (phase == Phase.ANSWERING) giveUp("không gửi được lệnh nghe: $message") else emptyList()

    private fun onAnswerTimeout(callPhase: CallPhase): List<SessionEffect> {
        if (phase != Phase.ANSWERING) return emptyList()
        return when (callPhase) {
            // Callback trễ hoặc bị bỏ lỡ nhưng cuộc gọi đã nghe được: đi tiếp.
            CallPhase.ACTIVE -> becomeActive()
            // Lệnh nghe không có hiệu lực: tuyệt đối không cúp, trả về đổ chuông.
            CallPhase.RINGING -> giveUp("lệnh nghe không có hiệu lực sau $ANSWER_TIMEOUT_MS ms")
            else -> finish("cuộc gọi đã kết thúc trong lúc chờ nghe")
        }
    }

    private fun onRouteEnsured(ok: Boolean): List<SessionEffect> {
        if (phase != Phase.ACTIVE) return emptyList()
        if (!ok) return disconnect("không ép được âm thanh về loa trong/tai nghe có dây")
        monitoring = true
        hangTimerRunning = true
        return listOf(SessionEffect.StartTimer(TimerKind.HANG_UP, settings.hangUpAfterSec * 1000L))
    }

    private fun onAudio(route: RouteKind, muted: Boolean): List<SessionEffect> {
        if (phase != Phase.ACTIVE || !monitoring) return emptyList()
        // Cuộc ẩn mà âm thanh rời route riêng tư: cúp ngay (người gọi/loa ngoài có thể lộ âm thanh).
        if (hidden && route == RouteKind.OTHER) return disconnect("âm thanh rời khỏi loa trong/tai nghe có dây")
        if (settings.muteMic) {
            if (muted) {
                sawMuted = true
            } else if (sawMuted && !hidden && hangTimerRunning) {
                // Người dùng chủ động bật lại micro: họ muốn nói chuyện, hủy hẹn tự cúp.
                hangTimerRunning = false
                return listOf(
                    SessionEffect.CancelTimer(TimerKind.HANG_UP),
                    SessionEffect.Note("người dùng bật lại micro: hủy hẹn tự cúp")
                )
            }
        }
        return emptyList()
    }

    private fun onOtherCall(callPhase: CallPhase): List<SessionEffect> = when (phase) {
        // Đã gửi lệnh nghe nhưng cuộc gọi vẫn đang đổ chuông: cúp lúc này thực chất là từ chối người gọi,
        // nên chỉ bỏ cuộc (trả về đổ chuông). Đã ACTIVE hoặc trạng thái khác thì cúp như bình thường.
        Phase.ANSWERING ->
            if (callPhase == CallPhase.RINGING) giveUp("có cuộc gọi thứ hai") else disconnect("có cuộc gọi thứ hai")
        Phase.ACTIVE -> disconnect("có cuộc gọi thứ hai")
        Phase.DELAYING, Phase.NEW -> giveUp("có cuộc gọi thứ hai")
        Phase.FINISHED -> emptyList()
    }

    // ---- các kiểu kết thúc

    private fun finish(reason: String): List<SessionEffect> = terminate(reason, emptyList())

    private fun giveUp(reason: String): List<SessionEffect> =
        terminate(
            reason,
            listOf(
                SessionEffect.Note("không tự trả lời được: $reason"),
                SessionEffect.GiveUp(reason, restoreRinging = hidden)
            )
        )

    private fun disconnect(reason: String): List<SessionEffect> =
        terminate(reason, listOf(SessionEffect.Note("đã cúp: $reason"), SessionEffect.Disconnect(reason)))

    private fun terminate(reason: String, lead: List<SessionEffect>): List<SessionEffect> {
        val wasActive = phase == Phase.ACTIVE
        phase = Phase.FINISHED
        val out = ArrayList<SessionEffect>()
        out.addAll(lead)
        out += SessionEffect.CancelTimer(TimerKind.DELAY)
        out += SessionEffect.CancelTimer(TimerKind.ANSWER_TIMEOUT)
        out += SessionEffect.CancelTimer(TimerKind.HANG_UP)
        if (muteRequested && !wasActive) out += SessionEffect.RestoreMute
        out += SessionEffect.Finish(reason)
        return out
    }

    companion object {
        const val ANSWER_TIMEOUT_MS = 4000L
    }
}
