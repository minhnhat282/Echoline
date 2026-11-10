package com.minhnhat.echoline.telecom

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.os.SystemClock
import android.telecom.VideoProfile
import com.minhnhat.echoline.diag.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/** Một cuộc gọi đang sống trong InCallService. */
class CallEntry(val call: Call, val incoming: Boolean) {
    val id: Int = nextId.incrementAndGet()

    /** Thời điểm (elapsedRealtime) InCallService nhận cuộc gọi này, để đo thời gian đến lúc nghe máy. */
    val addedAt: Long = SystemClock.elapsedRealtime()

    @Volatile var hidden: Boolean = false
    @Volatile var number: String? = null
    @Volatile var name: String? = null
    @Volatile var simSlot: Int? = null
    @Volatile var simLabel: String? = null

    /** Thời điểm dòng nhật ký quyết định của cuộc gọi này (để ghi chú thêm), 0 nếu chưa có. */
    @Volatile var logTimeMs: Long = 0L

    var callback: Call.Callback? = null

    private companion object {
        val nextId = AtomicInteger(0)
    }
}

/** Ảnh chụp cuộc gọi cho giao diện. */
data class CallUi(
    val id: Int,
    val number: String?,
    val name: String?,
    val state: Int,
    val incoming: Boolean,
    val simLabel: String?,
    val connectTimeMs: Long,
    val canHold: Boolean,
    val hidden: Boolean
)

data class AudioUi(val muted: Boolean, val route: Int, val supportedMask: Int)

@Suppress("DEPRECATION")
fun stateOf(call: Call): Int = call.state

fun isRingingState(state: Int): Boolean =
    state == Call.STATE_RINGING || state == Call.STATE_SIMULATED_RINGING

fun isLiveState(state: Int): Boolean =
    state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING

/**
 * Nơi DUY NHẤT giữ các cuộc gọi đang sống. Trạng thái "đang có cuộc khác" luôn tính theo trạng thái thật của Call
 * (không có cờ riêng có thể bị kẹt).
 */
class CallRegistry {
    private val lock = Any()
    private val entries = LinkedHashMap<Call, CallEntry>()

    private val _calls = MutableStateFlow<List<CallUi>>(emptyList())
    val calls: StateFlow<List<CallUi>> = _calls.asStateFlow()

    private val _audio = MutableStateFlow(AudioUi(muted = false, route = 0, supportedMask = 0))
    val audio: StateFlow<AudioUi> = _audio.asStateFlow()

    @Volatile var service: InCallService? = null

    fun add(entry: CallEntry) {
        synchronized(lock) { entries[entry.call] = entry }
        publish()
    }

    fun remove(call: Call): CallEntry? {
        val removed = synchronized(lock) { entries.remove(call) }
        publish()
        return removed
    }

    private fun entryById(id: Int): CallEntry? = synchronized(lock) { entries.values.firstOrNull { it.id == id } }

    /** Số cuộc gọi còn sống, không tính [excluding]. */
    fun liveCount(excluding: Call?): Int = synchronized(lock) {
        entries.values.count { it.call !== excluding && isLiveState(stateOf(it.call)) }
    }

    fun publish() {
        val list = synchronized(lock) { entries.values.map(::toUi) }
        _calls.value = list
    }

    fun snapshot(): List<CallUi> = _calls.value

    fun setAudio(state: CallAudioState?) {
        if (state == null) return
        _audio.value = AudioUi(state.isMuted, state.route, state.supportedRouteMask)
    }

    private fun toUi(e: CallEntry): CallUi {
        val details = try {
            e.call.details
        } catch (ex: Exception) {
            null
        }
        return CallUi(
            id = e.id,
            number = e.number,
            name = e.name,
            state = stateOf(e.call),
            incoming = e.incoming,
            simLabel = e.simLabel,
            connectTimeMs = details?.connectTimeMillis ?: 0L,
            canHold = details?.can(Call.Details.CAPABILITY_HOLD) == true,
            hidden = e.hidden
        )
    }

    // ---- Lệnh từ giao diện / thông báo. Mọi lệnh Telecom đi qua đây, có bắt ngoại lệ (cuộc gọi có thể vừa kết thúc).

    fun answer(id: Int) = command(id, "trả lời") { it.answer(VideoProfile.STATE_AUDIO_ONLY) }

    fun reject(id: Int) = command(id, "từ chối") { it.reject(false, null) }

    fun hangup(id: Int) = command(id, "cúp máy") { it.disconnect() }

    fun hold(id: Int, hold: Boolean) = command(id, if (hold) "giữ máy" else "bỏ giữ") {
        if (hold) it.hold() else it.unhold()
    }

    fun dtmf(id: Int, digit: Char, down: Boolean) = command(id, "DTMF") {
        if (down) it.playDtmfTone(digit) else it.stopDtmfTone()
    }

    fun setMuted(muted: Boolean) {
        try {
            service?.setMuted(muted)
        } catch (e: Exception) {
            Diag.e(TAG, "Không đổi được trạng thái micro", e)
        }
    }

    @Suppress("DEPRECATION")
    fun setRoute(route: Int) {
        try {
            service?.setAudioRoute(route)
        } catch (e: Exception) {
            Diag.e(TAG, "Không đổi được route âm thanh", e)
        }
    }

    private fun command(id: Int, what: String, block: (Call) -> Unit) {
        val entry = entryById(id)
        if (entry == null) {
            Diag.w(TAG, "Lệnh '$what' cho cuộc gọi không còn tồn tại (id=$id)")
            return
        }
        try {
            block(entry.call)
        } catch (e: Exception) {
            Diag.e(TAG, "Lệnh '$what' thất bại", e)
        }
    }

    private companion object {
        const val TAG = "calls"
    }
}
