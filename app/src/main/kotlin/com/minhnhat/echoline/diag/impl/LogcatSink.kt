package com.minhnhat.echoline.diag.impl

import android.util.Log
import com.minhnhat.echoline.diag.DiagRecord
import com.minhnhat.echoline.diag.DiagSink
import com.minhnhat.echoline.diag.Level

/** Ghi ra Logcat (lọc theo tiền tố "Echo/"). */
class LogcatSink : DiagSink {
    override fun write(record: DiagRecord) {
        val tag = ("Echo/" + record.tag).take(23)
        when (record.level) {
            Level.DEBUG -> Log.d(tag, record.message, record.throwable)
            Level.INFO -> Log.i(tag, record.message, record.throwable)
            Level.WARN -> Log.w(tag, record.message, record.throwable)
            Level.ERROR -> Log.e(tag, record.message, record.throwable)
        }
    }
}
