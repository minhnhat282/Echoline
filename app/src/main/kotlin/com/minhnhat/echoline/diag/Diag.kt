package com.minhnhat.echoline.diag

/**
 * LÕI của hệ thống ghi nhật ký lỗi. Toàn bộ ứng dụng chỉ gọi các hàm của [Diag] (d, i, w, e, guard).
 *
 * Dễ thay đổi: viết thêm một [DiagSink] mới (ví dụ gửi lên máy chủ) rồi thêm vào danh sách trong
 * `impl/DiagSetup.kt`.
 *
 * Dễ gỡ bỏ:
 *  - Tắt: bỏ dòng `DiagSetup.install(this)` trong App.kt. Khi đó [Diag] không có nơi ghi và mọi lệnh log thành "không làm gì".
 *  - Xóa hẳn: xóa thư mục `diag/impl` và `diag/ui`, xóa 2 chỗ tham chiếu (App.kt, ui/AppRoot.kt, xem README),
 *    giữ lại duy nhất tệp này.
 */
enum class Level(val letter: Char) { DEBUG('D'), INFO('I'), WARN('W'), ERROR('E') }

class DiagRecord(
    val timeMs: Long,
    val level: Level,
    val tag: String,
    val message: String,
    val throwable: Throwable?
)

/** Nơi nhận bản ghi. Không được ném ngoại lệ (nếu ném, [Diag] sẽ nuốt). */
fun interface DiagSink {
    fun write(record: DiagRecord)
}

object Diag {
    @Volatile
    private var sinks: List<DiagSink> = emptyList()

    /** Bản ghi có mức thấp hơn mức này bị bỏ qua. */
    @Volatile
    var minLevel: Level = Level.DEBUG

    fun install(newSinks: List<DiagSink>) {
        sinks = newSinks.toList()
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)

    fun w(tag: String, message: String, t: Throwable? = null) = log(Level.WARN, tag, message, t)

    fun e(tag: String, message: String, t: Throwable? = null) = log(Level.ERROR, tag, message, t)

    /** Chạy [block]; nếu ném ngoại lệ thì ghi lỗi và trả về null thay vì làm sập ứng dụng. */
    fun <T> guard(tag: String, what: String, block: () -> T): T? {
        return try {
            block()
        } catch (t: Exception) {
            e(tag, "$what: ${t.javaClass.simpleName}: ${t.message}", t)
            null
        }
    }

    private fun log(level: Level, tag: String, message: String, t: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        val current = sinks
        if (current.isEmpty()) return
        val record = DiagRecord(System.currentTimeMillis(), level, tag, message, t)
        for (sink in current) {
            try {
                sink.write(record)
            } catch (ignored: Throwable) {
                // ghi nhật ký không bao giờ được làm hỏng luồng chính
            }
        }
    }
}
