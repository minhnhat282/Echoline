package com.minhnhat.echoline.diag.impl

import android.util.Log
import com.minhnhat.echoline.diag.DiagRecord
import com.minhnhat.echoline.diag.DiagSink
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Ghi vào tệp trong bộ nhớ riêng của ứng dụng, xoay vòng: "log-current.txt" đầy thì đổi thành "log-previous.txt".
 * Tổng tối đa khoảng 2 x [maxBytes]. Việc ghi chạy ở một luồng nền riêng nên không làm chậm cuộc gọi.
 */
class FileSink(
    private val dir: File,
    private val maxBytes: Long = 512 * 1024L,
    private val header: () -> String
) : DiagSink {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "echo-diag").also { it.isDaemon = true }
    }
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun write(record: DiagRecord) {
        try {
            executor.execute { append(record) }
        } catch (ignored: RejectedExecutionException) {
            // đã tắt
        }
    }

    private fun append(r: DiagRecord) {
        try {
            dir.mkdirs()
            val current = File(dir, DiagStore.CURRENT)
            if (current.length() > maxBytes) {
                val previous = File(dir, DiagStore.PREVIOUS)
                previous.delete()
                current.renameTo(previous)
            }
            val isNew = !current.exists()
            FileOutputStream(current, true).use { out ->
                if (isNew) out.write(header().toByteArray())
                out.write(format(r).toByteArray())
            }
        } catch (ignored: Exception) {
            // không có chỗ ghi thì thôi
        }
    }

    private fun format(r: DiagRecord): String {
        val sb = StringBuilder()
        sb.append(timeFormat.format(r.timeMs)).append(' ')
            .append(r.level.letter).append('/').append(r.tag).append(": ").append(r.message)
        if (r.throwable != null) sb.append('\n').append(Log.getStackTraceString(r.throwable))
        sb.append('\n')
        return sb.toString()
    }
}
