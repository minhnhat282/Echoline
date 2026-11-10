package com.minhnhat.echoline.diag.impl

import android.content.Context
import android.os.Build
import com.minhnhat.echoline.diag.Diag
import java.io.File

/** Nơi DUY NHẤT lắp ráp hệ thống nhật ký. Muốn đổi nơi ghi thì sửa danh sách bên dưới. */
object DiagSetup {
    private var installedStore: DiagStore? = null

    /** Nơi đọc nhật ký cho màn hình xem. Nếu chưa cài đặt (đã tắt) thì trả về kho rỗng. */
    val store: DiagStore
        get() = installedStore ?: DiagStore(File(""))

    fun install(context: Context) {
        val dir = File(context.filesDir, "diag")
        installedStore = DiagStore(dir)
        val header = { headerText(context) }
        Diag.install(listOf(LogcatSink(), FileSink(dir, header = header)))
        CrashHandler(dir, header).install()
        Diag.i("diag", "Tiến trình khởi động")
    }

    private fun headerText(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (ignored: Exception) {
            "?"
        }
        return "=== Echoline $version | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | " +
            "${Build.MANUFACTURER} ${Build.MODEL} ===\n"
    }
}
