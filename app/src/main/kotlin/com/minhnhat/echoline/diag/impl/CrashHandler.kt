package com.minhnhat.echoline.diag.impl

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ghi báo cáo sập ứng dụng (ghi đồng bộ vì tiến trình sắp chết) rồi chuyển tiếp cho bộ xử lý mặc định của hệ thống.
 */
class CrashHandler(
    private val dir: File,
    private val header: () -> String
) : Thread.UncaughtExceptionHandler {

    private var previous: Thread.UncaughtExceptionHandler? = null

    fun install() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "${DiagStore.CRASH_PREFIX}$stamp.txt")
            file.writeText(header() + "SẬP ỨNG DỤNG trên luồng '${t.name}'\n" + Log.getStackTraceString(e) + "\n")
            pruneOld()
        } catch (ignored: Throwable) {
            // không làm gì thêm
        }
        previous?.uncaughtException(t, e)
    }

    private fun pruneOld() {
        val crashes = dir.listFiles { f -> f.name.startsWith(DiagStore.CRASH_PREFIX) }?.sortedBy { it.name } ?: return
        for (old in crashes.dropLast(MAX_CRASH_FILES)) old.delete()
    }

    private companion object {
        const val MAX_CRASH_FILES = 5
    }
}
