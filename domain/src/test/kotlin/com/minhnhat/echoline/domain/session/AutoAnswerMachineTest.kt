package com.minhnhat.echoline.domain.session

import com.minhnhat.echoline.domain.model.AutoAnswerSettings
import com.minhnhat.echoline.domain.session.SessionEffect.AnswerCall
import com.minhnhat.echoline.domain.session.SessionEffect.ApplyMute
import com.minhnhat.echoline.domain.session.SessionEffect.CancelTimer
import com.minhnhat.echoline.domain.session.SessionEffect.Disconnect
import com.minhnhat.echoline.domain.session.SessionEffect.EnsurePrivateRoute
import com.minhnhat.echoline.domain.session.SessionEffect.Finish
import com.minhnhat.echoline.domain.session.SessionEffect.GiveUp
import com.minhnhat.echoline.domain.session.SessionEffect.MarkAnswered
import com.minhnhat.echoline.domain.session.SessionEffect.RestoreMute
import com.minhnhat.echoline.domain.session.SessionEffect.StartTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAnswerMachineTest {

    private val ok = Snapshot(CallPhase.RINGING, otherLiveCalls = 0, hasPrivateRoute = true)

    private fun settings(mute: Boolean = true, hang: Int = 60) =
        AutoAnswerSettings(enabled = true, delaySec = 0, muteMic = mute, hangUpAfterSec = hang, historyWindowMin = 1440)

    /** Máy đã ở trạng thái ANSWERING (chế độ ẩn). */
    private fun answering(hidden: Boolean = true, mute: Boolean = true): AutoAnswerMachine {
        val m = AutoAnswerMachine(hidden, if (hidden) 0 else 5, settings(mute))
        if (hidden) {
            m.handle(SessionEvent.Begin(ok))
        } else {
            m.handle(SessionEvent.Begin(ok))
            m.handle(SessionEvent.DelayElapsed(ok))
        }
        assertEquals(AutoAnswerMachine.Phase.ANSWERING, m.phase)
        return m
    }

    /** Máy đã ở trạng thái ACTIVE và đã ép route thành công (đang giám sát). */
    private fun monitored(hidden: Boolean = true, mute: Boolean = true): AutoAnswerMachine {
        val m = answering(hidden, mute)
        m.handle(SessionEvent.CallPhaseChanged(CallPhase.ACTIVE))
        m.handle(SessionEvent.RouteEnsured(true))
        assertEquals(AutoAnswerMachine.Phase.ACTIVE, m.phase)
        return m
    }

    private fun List<SessionEffect>.hasGiveUp() = any { it is GiveUp }
    private fun List<SessionEffect>.hasDisconnect() = any { it is Disconnect }
    private fun List<SessionEffect>.finished() = any { it is Finish }

    // ---------------------------------------------------------------- bắt đầu và quyết định

    @Test
    fun `che do an nghe ngay, tat micro va dat gio cho nghe`() {
        val m = AutoAnswerMachine(hidden = true, delaySec = 0, settings = settings())
        val effects = m.handle(SessionEvent.Begin(ok))
        assertEquals(
            listOf(AnswerCall, ApplyMute, StartTimer(TimerKind.ANSWER_TIMEOUT, AutoAnswerMachine.ANSWER_TIMEOUT_MS)),
            effects
        )
        assertEquals(AutoAnswerMachine.Phase.ANSWERING, m.phase)
    }

    @Test
    fun `tat muteMic thi khong yeu cau tat micro`() {
        val m = AutoAnswerMachine(true, 0, settings(mute = false))
        val effects = m.handle(SessionEvent.Begin(ok))
        assertFalse(effects.contains(ApplyMute))
        assertTrue(effects.contains(AnswerCall))
    }

    @Test
    fun `che do co giao dien cho roi moi nghe`() {
        val m = AutoAnswerMachine(hidden = false, delaySec = 5, settings = settings())
        assertEquals(listOf(StartTimer(TimerKind.DELAY, 5000L)), m.handle(SessionEvent.Begin(ok)))
        assertEquals(AutoAnswerMachine.Phase.DELAYING, m.phase)
        val effects = m.handle(SessionEvent.DelayElapsed(ok))
        assertTrue(effects.contains(AnswerCall))
        assertEquals(AutoAnswerMachine.Phase.ANSWERING, m.phase)
    }

    @Test
    fun `che do co giao dien nhung delay 0 thi nghe ngay`() {
        val m = AutoAnswerMachine(hidden = false, delaySec = 0, settings = settings())
        assertTrue(m.handle(SessionEvent.Begin(ok)).contains(AnswerCall))
    }

    @Test
    fun `nguoi dung tu tra loi trong luc cho thi ket thuc, khong can thiep`() {
        val m = AutoAnswerMachine(false, 5, settings())
        m.handle(SessionEvent.Begin(ok))
        val effects = m.handle(SessionEvent.CallPhaseChanged(CallPhase.ACTIVE))
        assertTrue(effects.finished())
        assertFalse(effects.hasGiveUp())
        assertFalse(effects.hasDisconnect())
        assertFalse(effects.contains(AnswerCall))
        assertEquals(AutoAnswerMachine.Phase.FINISHED, m.phase)
    }

    @Test
    fun `het gio cho nhung cuoc goi khong con do chuong thi ket thuc`() {
        val m = AutoAnswerMachine(false, 5, settings())
        m.handle(SessionEvent.Begin(ok))
        val effects = m.handle(SessionEvent.DelayElapsed(Snapshot(CallPhase.ENDED, 0, true)))
        assertTrue(effects.finished())
        assertFalse(effects.hasGiveUp())
        assertFalse(effects.contains(AnswerCall))
    }

    @Test
    fun `co cuoc goi khac thi khong nghe, che do an phai phat chuong thay the`() {
        val m = AutoAnswerMachine(true, 0, settings())
        val effects = m.handle(SessionEvent.Begin(Snapshot(CallPhase.RINGING, otherLiveCalls = 1, hasPrivateRoute = true)))
        val giveUp = effects.filterIsInstance<GiveUp>().single()
        assertTrue(giveUp.restoreRinging)
        assertFalse(effects.contains(AnswerCall))
        assertTrue(effects.finished())
    }

    @Test
    fun `co cuoc goi khac o che do co giao dien thi khong can chuong thay the`() {
        val m = AutoAnswerMachine(false, 5, settings())
        m.handle(SessionEvent.Begin(ok))
        val effects = m.handle(SessionEvent.DelayElapsed(Snapshot(CallPhase.RINGING, 2, true)))
        assertFalse(effects.filterIsInstance<GiveUp>().single().restoreRinging)
    }

    @Test
    fun `khong co route rieng tu thi khong nghe`() {
        val m = AutoAnswerMachine(true, 0, settings())
        val effects = m.handle(SessionEvent.Begin(Snapshot(CallPhase.RINGING, 0, hasPrivateRoute = false)))
        assertTrue(effects.hasGiveUp())
        assertFalse(effects.contains(AnswerCall))
        assertFalse(effects.contains(ApplyMute))
    }

    // ---------------------------------------------------------------- chờ ACTIVE

    @Test
    fun `nghe thanh cong thi danh dau, ep route`() {
        val m = answering()
        val effects = m.handle(SessionEvent.CallPhaseChanged(CallPhase.ACTIVE))
        assertTrue(effects.contains(CancelTimer(TimerKind.ANSWER_TIMEOUT)))
        assertTrue(effects.contains(MarkAnswered))
        assertTrue(effects.contains(EnsurePrivateRoute))
        assertEquals(AutoAnswerMachine.Phase.ACTIVE, m.phase)
    }

    @Test
    fun `het gio cho nghe nhung van dang do chuong thi tra micro va do chuong khong cup`() {
        val m = answering()
        val effects = m.handle(SessionEvent.AnswerTimeout(CallPhase.RINGING))
        assertTrue(effects.hasGiveUp())
        assertTrue(effects.contains(RestoreMute))
        assertFalse(effects.hasDisconnect())
        assertTrue(effects.finished())
    }

    @Test
    fun `het gio cho nghe nhung cuoc goi da ACTIVE thi di tiep`() {
        val m = answering()
        val effects = m.handle(SessionEvent.AnswerTimeout(CallPhase.ACTIVE))
        assertTrue(effects.contains(EnsurePrivateRoute))
        assertEquals(AutoAnswerMachine.Phase.ACTIVE, m.phase)
    }

    @Test
    fun `het gio cho nghe nhung cuoc goi da ket thuc thi chi ket thuc phien`() {
        val m = answering()
        val effects = m.handle(SessionEvent.AnswerTimeout(CallPhase.ENDED))
        assertTrue(effects.finished())
        assertFalse(effects.hasGiveUp())
        assertFalse(effects.hasDisconnect())
    }

    @Test
    fun `cuoc goi ket thuc truoc khi ACTIVE thi tra micro`() {
        val m = answering()
        val effects = m.handle(SessionEvent.CallPhaseChanged(CallPhase.ENDED))
        assertTrue(effects.contains(RestoreMute))
        assertTrue(effects.finished())
    }

    @Test
    fun `lenh nghe that bai thi do chuong va tra micro`() {
        val m = answering()
        val effects = m.handle(SessionEvent.AnswerFailed("loi"))
        assertTrue(effects.hasGiveUp())
        assertTrue(effects.contains(RestoreMute))
        assertFalse(effects.hasDisconnect())
        assertEquals(AutoAnswerMachine.Phase.FINISHED, m.phase)
    }

    @Test
    fun `lenh nghe that bai sau khi da ket thuc bi bo qua`() {
        val m = answering()
        m.handle(SessionEvent.CallRemoved)
        assertTrue(m.handle(SessionEvent.AnswerFailed("muon")).isEmpty())
    }

    // ---------------------------------------------------------------- sau khi đã nghe

    @Test
    fun `ep route that bai thi cup`() {
        val m = answering()
        m.handle(SessionEvent.CallPhaseChanged(CallPhase.ACTIVE))
        val effects = m.handle(SessionEvent.RouteEnsured(false))
        assertTrue(effects.hasDisconnect())
        assertFalse(effects.contains(RestoreMute)) // đã ACTIVE: việc trả micro do tầng Android làm khi cuộc gọi kết thúc
        assertTrue(effects.finished())
    }

    @Test
    fun `ep route thanh cong thi dat gio tu cup`() {
        val m = AutoAnswerMachine(true, 0, settings(hang = 45))
        m.handle(SessionEvent.Begin(ok))
        m.handle(SessionEvent.CallPhaseChanged(CallPhase.ACTIVE))
        val effects = m.handle(SessionEvent.RouteEnsured(true))
        assertEquals(listOf(StartTimer(TimerKind.HANG_UP, 45_000L)), effects)
    }

    @Test
    fun `het gio tu cup thi cup`() {
        val m = monitored()
        val effects = m.handle(SessionEvent.HangUpElapsed)
        assertTrue(effects.hasDisconnect())
        assertTrue(effects.finished())
    }

    @Test
    fun `che do an am thanh roi route rieng tu thi cup ngay`() {
        val m = monitored(hidden = true)
        assertTrue(m.handle(SessionEvent.AudioChanged(RouteKind.OTHER, muted = true)).hasDisconnect())
    }

    @Test
    fun `che do an route chua biet thi khong cup`() {
        val m = monitored(hidden = true)
        assertTrue(m.handle(SessionEvent.AudioChanged(RouteKind.UNKNOWN, muted = false)).isEmpty())
    }

    @Test
    fun `che do co giao dien sang loa ngoai thi khong cup`() {
        val m = monitored(hidden = false)
        assertFalse(m.handle(SessionEvent.AudioChanged(RouteKind.OTHER, muted = true)).hasDisconnect())
        assertEquals(AutoAnswerMachine.Phase.ACTIVE, m.phase)
    }

    @Test
    fun `che do co giao dien nguoi dung bat lai micro thi huy hen cup`() {
        val m = monitored(hidden = false)
        assertTrue(m.handle(SessionEvent.AudioChanged(RouteKind.PRIVATE, muted = true)).isEmpty())
        val effects = m.handle(SessionEvent.AudioChanged(RouteKind.PRIVATE, muted = false))
        assertTrue(effects.contains(CancelTimer(TimerKind.HANG_UP)))
        assertFalse(effects.finished())
        // Chỉ hủy một lần.
        assertTrue(m.handle(SessionEvent.AudioChanged(RouteKind.PRIVATE, muted = false)).isEmpty())
    }

    @Test
    fun `chua tung thay micro bi tat thi khong coi la nguoi dung bat lai`() {
        val m = monitored(hidden = false)
        assertTrue(m.handle(SessionEvent.AudioChanged(RouteKind.PRIVATE, muted = false)).isEmpty())
    }

    @Test
    fun `che do an khong huy hen cup khi micro duoc bat`() {
        val m = monitored(hidden = true)
        m.handle(SessionEvent.AudioChanged(RouteKind.PRIVATE, muted = true))
        assertTrue(m.handle(SessionEvent.AudioChanged(RouteKind.PRIVATE, muted = false)).isEmpty())
    }

    @Test
    fun `chua ep route xong thi chua giam sat am thanh`() {
        val m = answering(hidden = true)
        m.handle(SessionEvent.CallPhaseChanged(CallPhase.ACTIVE))
        assertTrue(m.handle(SessionEvent.AudioChanged(RouteKind.OTHER, muted = false)).isEmpty())
    }

    // ---------------------------------------------------------------- cuộc gọi thứ hai và kết thúc

    @Test
    fun `cuoc thu hai den khi da nghe thi cup`() {
        assertTrue(monitored().handle(SessionEvent.OtherCallArrived(CallPhase.ACTIVE)).hasDisconnect())
        val m = answering()
        m.handle(SessionEvent.CallPhaseChanged(CallPhase.ACTIVE))
        assertTrue(m.handle(SessionEvent.OtherCallArrived(CallPhase.ACTIVE)).hasDisconnect())
    }

    @Test
    fun `cuoc thu hai den khi lenh nghe chua co hieu luc thi khong cup, tra ve do chuong`() {
        val effects = answering().handle(SessionEvent.OtherCallArrived(CallPhase.RINGING))
        assertTrue(effects.hasGiveUp())
        assertFalse(effects.hasDisconnect())
        assertTrue(effects.finished())
    }

    @Test
    fun `cuoc thu hai den khi dang cho delay thi huy hen, khong tu choi`() {
        val m = AutoAnswerMachine(false, 5, settings())
        m.handle(SessionEvent.Begin(ok))
        val effects = m.handle(SessionEvent.OtherCallArrived(CallPhase.RINGING))
        assertTrue(effects.hasGiveUp())
        assertFalse(effects.hasDisconnect())
        assertTrue(effects.contains(CancelTimer(TimerKind.DELAY)))
    }

    @Test
    fun `cuoc thu hai den khi dang cho nghe thi tra micro`() {
        assertTrue(answering().handle(SessionEvent.OtherCallArrived(CallPhase.RINGING)).contains(RestoreMute))
    }

    @Test
    fun `sau khi ket thuc moi su kien deu bi bo qua`() {
        val m = monitored()
        m.handle(SessionEvent.CallRemoved)
        assertEquals(AutoAnswerMachine.Phase.FINISHED, m.phase)
        assertTrue(m.handle(SessionEvent.HangUpElapsed).isEmpty())
        assertTrue(m.handle(SessionEvent.OtherCallArrived(CallPhase.RINGING)).isEmpty())
        assertTrue(m.handle(SessionEvent.Begin(ok)).isEmpty())
    }

    @Test
    fun `huy dich vu thi ket thuc va huy moi hen gio`() {
        val m = monitored()
        val effects = m.handle(SessionEvent.Shutdown)
        assertTrue(effects.contains(CancelTimer(TimerKind.HANG_UP)))
        assertTrue(effects.finished())
    }

    @Test
    fun `Begin chi co tac dung mot lan`() {
        val m = AutoAnswerMachine(true, 0, settings())
        m.handle(SessionEvent.Begin(ok))
        assertTrue(m.handle(SessionEvent.Begin(ok)).isEmpty())
    }
}
