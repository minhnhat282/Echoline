package com.minhnhat.echoline

import android.app.Application
import com.minhnhat.echoline.data.AppPrefs
import com.minhnhat.echoline.data.ContactsIndex
import com.minhnhat.echoline.data.DecisionLog
import com.minhnhat.echoline.data.EmergencyTracker
import com.minhnhat.echoline.data.FilterConfigStore
import com.minhnhat.echoline.data.RecentNumbers
import com.minhnhat.echoline.data.SimRepository
import com.minhnhat.echoline.diag.Diag
import com.minhnhat.echoline.domain.number.PhoneKeys
import com.minhnhat.echoline.telecom.AudioProbe
import com.minhnhat.echoline.telecom.CallNotifications
import com.minhnhat.echoline.telecom.CallRegistry
import com.minhnhat.echoline.telecom.DecisionRegistry
import com.minhnhat.echoline.telecom.FallbackRinger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Nơi duy nhất tạo và giữ các thành phần dùng chung (một bản cho cả tiến trình). Không dùng thư viện DI.
 * Được tạo trong Application.onCreate, nên luôn sẵn sàng trước khi bất kỳ Service/Activity nào chạy.
 */
class AppGraph(app: Application) {

    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Diag.e("scope", "Lỗi coroutine không bắt", t) }
    )

    val prefs = AppPrefs(app)
    val sims = SimRepository(app)
    val configStore = FilterConfigStore(app) { sims.region() }
    val contacts = ContactsIndex(app, { sims.indexRegions() }, appScope)
    val recents = RecentNumbers(app, { sims.indexRegions() }, appScope)
    val emergency = EmergencyTracker(app, prefs)
    val decisionLog = DecisionLog(app, appScope)
    val decisions = DecisionRegistry()
    val callRegistry = CallRegistry()
    val audio = AudioProbe(app, prefs)
    val notifications = CallNotifications(app)
    val ringer = FallbackRinger(app, appScope)

    fun start() {
        sims.onSimsChanged = {
            contacts.reload()
            recents.reload()
        }
        // Việc BẮT BUỘC cho cuộc gọi đầu tiên: làm ngay, đồng bộ.
        sims.start() // phải ở luồng chính (listener cần Looper)
        configStore.loadBlocking()
        contacts.start()
        recents.start()
        audio.restoreMicIfNeeded() // phải xong trước mọi cuộc gọi: có thể đang giữ cờ tắt micro của lần trước
        // Việc không nằm trên đường xử lý cuộc gọi: để nền.
        appScope.launch {
            Diag.guard("startup", "khởi tạo nền") {
                notifications.ensureChannels()
                PhoneKeys.warmUp(sims.region())
            }
        }
    }

    /** Gọi sau khi người dùng cấp/thu quyền hoặc đổi vai trò. */
    fun onPermissionsChanged() {
        sims.invalidate()
        contacts.reload()
        recents.reload()
    }
}
