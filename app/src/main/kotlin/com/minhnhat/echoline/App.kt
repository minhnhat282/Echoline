package com.minhnhat.echoline

import android.app.Application
import com.minhnhat.echoline.domain.number.PhoneKeys
import kotlin.concurrent.thread
import com.minhnhat.echoline.diag.impl.DiagSetup

class App : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Nạp libphonenumber NGAY, song song với phần khởi tạo còn lại, để cuộc gọi đầu tiên không phải trả giá này.
        thread(name = "echo-warm", isDaemon = true) {
            try {
                PhoneKeys.warmUp(PhoneKeys.DEFAULT_REGION)
            } catch (ignored: Throwable) {
                // nạp thất bại thì lần dùng đầu sẽ tự nạp
            }
        }
        // Nhật ký lỗi (gỡ bỏ: xóa dòng này, xem README). Phải chạy trước mọi thứ khác.
        DiagSetup.install(this)
        graph = AppGraph(this)
        graph.start()
    }
}
