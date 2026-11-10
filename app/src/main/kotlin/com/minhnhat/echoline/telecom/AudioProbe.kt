package com.minhnhat.echoline.telecom

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.minhnhat.echoline.data.AppPrefs
import com.minhnhat.echoline.data.systemService
import com.minhnhat.echoline.diag.Diag

/** Kiểm tra thiết bị âm thanh (để chỉ tự trả lời khi có loa trong hoặc tai nghe có dây) và khôi phục micro. */
class AudioProbe(context: Context, private val prefs: AppPrefs) {
    private val audio: AudioManager = context.systemService()

    private fun outputs(): Array<AudioDeviceInfo> = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    fun hasWiredHeadset(): Boolean = try {
        outputs().any { it.type in WIRED_TYPES }
    } catch (e: Exception) {
        false
    }

    /**
     * Có đường âm thanh riêng tư (loa trong hoặc tai nghe có dây) để tự trả lời an toàn.
     *
     * Loa trong luôn có mặt trong danh sách thiết bị của điện thoại, kể cả khi đang dùng Bluetooth, nên điều kiện thật sự là
     * KHÔNG có thiết bị ngoài nào (Bluetooth, trợ thính) đang kết nối: khi có, Telecom thường chuyển âm thanh cuộc gọi sang đó
     * ngay lúc nghe máy (tai nghe, loa xe), nên không được coi là riêng tư. Tai nghe chỉ phát nhạc (A2DP) cũng bị tính, nên khi
     * đang kết nối chúng thì cuộc gọi đổ chuông bình thường (an toàn hơn). Loa ngoài chỉ xuất hiện khi đã trong cuộc gọi nên
     * được kiểm sau khi nghe (ép route và giám sát âm thanh).
     *
     * Chỉ lấy danh sách thiết bị MỘT lần (mỗi lần gọi là một lệnh Binder) và tính mọi điều kiện trên cùng bản chụp đó.
     */
    fun hasPrivateRoute(): Boolean = try {
        val types = outputs().map { it.type }
        // Không đọc được danh sách thì không dám khẳng định là riêng tư (ném ngoại lệ -> false ở dưới).
        types.none { it in EXTERNAL_TYPES } &&
            (types.any { it in WIRED_TYPES } || AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in types)
    } catch (e: Exception) {
        false
    }

    private companion object {
        val WIRED_TYPES: Set<Int> = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )

        val EXTERNAL_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
            }
        }
    }

    /**
     * Đường DỰ PHÒNG khôi phục micro: nếu ứng dụng đã tắt micro mà phiên không trả lại được (lệnh thất bại, hoặc tiến trình bị kill
     * giữa chừng), cờ [AppPrefs.micMutedByUs] vẫn còn và hàm này bật lại micro qua [AudioManager]. Đường chính là
     * `InCallService.setMuted(false)` của phiên (xem AutoAnswerRunner). Dùng AudioManager vì không còn InCallService trong tay lúc
     * khởi động; Telecom đổi mute qua chính cơ chế này nên có hiệu lực, nhưng đó là chi tiết cài đặt của hệ thống, nên chỉ là dự phòng.
     * Cờ chỉ được xóa khi khôi phục thành công.
     */
    fun restoreMicIfNeeded() {
        if (!prefs.micMutedByUs) return
        try {
            if (audio.isMicrophoneMute) audio.isMicrophoneMute = false
            Diag.i("audio", "Đã khôi phục micro sau cuộc tự trả lời")
            prefs.micMutedByUs = false
        } catch (e: Exception) {
            Diag.e("audio", "Không khôi phục được micro (giữ cờ để thử lại)", e)
        }
    }
}
