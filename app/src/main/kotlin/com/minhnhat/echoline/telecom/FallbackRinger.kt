package com.minhnhat.echoline.telecom

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import com.minhnhat.echoline.data.systemService
import com.minhnhat.echoline.diag.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Chuông thay thế: dùng khi hệ thống đã tắt chuông (cuộc định tự trả lời ẩn) nhưng cuối cùng không tự trả lời được,
 * để cuộc gọi không hiện ra trong im lặng. Theo chế độ chuông của máy: chuông, rung hoặc im.
 * Tự dừng khi gọi [stop] hoặc sau 60 giây.
 *
 * Việc dựng và phát chuông (truy vấn nhạc chuông, khởi tạo trình phát) chạy ở luồng IO, không chặn luồng chính của cuộc gọi.
 * Bộ đếm [generation] bảo đảm một lệnh [stop] gọi trong lúc đang dựng sẽ thắng: chuông vừa dựng xong sẽ không được phát.
 */
class FallbackRinger(private val context: Context, private val scope: CoroutineScope) {
    private val audio: AudioManager = context.systemService()

    private val lock = Any()
    private var generation = 0
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var stopJob: Job? = null

    fun start() {
        val myGeneration = synchronized(lock) {
            stopLocked()
            ++generation
        }
        scope.launch(Dispatchers.IO) { play(myGeneration) }
        val job = scope.launch {
            delay(MAX_MS)
            stop()
        }
        synchronized(lock) {
            if (generation == myGeneration) stopJob = job else job.cancel()
        }
    }

    fun stop() {
        synchronized(lock) {
            generation++
            stopLocked()
        }
    }

    @Suppress("DEPRECATION")
    private fun play(myGeneration: Int) {
        try {
            val mode = audio.ringerMode
            var tone: Ringtone? = null
            if (mode == AudioManager.RINGER_MODE_NORMAL) {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                tone = RingtoneManager.getRingtone(context, uri)?.also { r ->
                    r.audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                    r.isLooping = true
                }
            }
            val vib = if (mode != AudioManager.RINGER_MODE_SILENT) context.systemService<Vibrator>() else null

            synchronized(lock) {
                if (generation != myGeneration) {
                    // Đã có lệnh dừng (hoặc lệnh phát mới) trong lúc dựng: bỏ.
                    return
                }
                // Giữ tham chiếu TRƯỚC khi phát: nếu một bước phát ném ngoại lệ, stopLocked() vẫn dừng được những gì đã bắt đầu.
                ringtone = tone
                vibrator = vib
                try {
                    tone?.play()
                    vib?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 1000), 0))
                } catch (e: Exception) {
                    stopLocked()
                    throw e
                }
            }
            Diag.i(TAG, "Phát chuông thay thế (chế độ chuông=$mode)")
        } catch (e: Exception) {
            Diag.e(TAG, "Không phát được chuông thay thế", e)
        }
    }

    /** Gọi khi đang giữ [lock]. */
    private fun stopLocked() {
        stopJob?.cancel()
        stopJob = null
        try {
            ringtone?.stop()
            vibrator?.cancel()
        } catch (e: Exception) {
            Diag.w(TAG, "Lỗi khi dừng chuông thay thế: ${e.message}")
        }
        ringtone = null
        vibrator = null
    }

    private companion object {
        const val TAG = "ringer"
        const val MAX_MS = 60_000L
    }
}
