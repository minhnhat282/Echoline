package com.minhnhat.echoline.diag.impl

import java.io.File

/** Đọc, gộp và xóa các tệp nhật ký (dùng cho màn hình "Nhật ký lỗi"). */
class DiagStore(private val dir: File) {

    /** Báo cáo sập (mới nhất trước), rồi nhật ký trước, rồi nhật ký hiện tại. */
    private fun files(): List<File> {
        val crashes = dir.listFiles { f -> f.name.startsWith(CRASH_PREFIX) }?.sortedByDescending { it.name }.orEmpty()
        return crashes + listOf(File(dir, PREVIOUS), File(dir, CURRENT)).filter { it.exists() }
    }

    fun readFull(): String {
        val sb = StringBuilder()
        for (f in files()) {
            try {
                sb.append("##### ").append(f.name).append(" #####\n").append(f.readText()).append('\n')
            } catch (ignored: Exception) {
                sb.append("##### ").append(f.name).append(" (không đọc được) #####\n")
            }
        }
        return sb.toString()
    }

    fun readTail(maxChars: Int): String {
        val full = readFull()
        return if (full.length <= maxChars) full else "…(đã cắt bớt phần đầu)…\n" + full.takeLast(maxChars)
    }

    fun sizeBytes(): Long = files().sumOf { it.length() }

    fun clear() {
        for (f in files()) f.delete()
    }

    companion object {
        const val CURRENT = "log-current.txt"
        const val PREVIOUS = "log-previous.txt"
        const val CRASH_PREFIX = "crash-"
    }
}
