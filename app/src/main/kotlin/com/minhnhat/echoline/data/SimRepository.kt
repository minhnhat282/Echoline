package com.minhnhat.echoline.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.minhnhat.echoline.diag.Diag
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class SimInfo(val slot: Int, val subscriptionId: Int, val label: String)

/**
 * Ánh xạ tài khoản gọi (PhoneAccountHandle) sang khe SIM (1-based), nhãn SIM và mã vùng để chuẩn hóa số.
 * Mọi lệnh cần READ_PHONE_STATE đều đi sau [hasPermission] và nằm trong try/catch.
 */
@android.annotation.SuppressLint("MissingPermission")
class SimRepository(private val context: Context) {
    private val telecom: TelecomManager = context.systemService()
    private val telephony: TelephonyManager = context.systemService()
    private val subs: SubscriptionManager = context.systemService()

    private class Cached(val slot: Int?, val atMs: Long)

    private val cache = ConcurrentHashMap<PhoneAccountHandle, Cached>()

    private class CachedRegion(val region: String, val atMs: Long)

    private val regionByHandle = ConcurrentHashMap<PhoneAccountHandle, CachedRegion>()

    /** Chữ ký danh sách SIM lần trước, để phân biệt thay đổi THẬT với lần gọi khởi tạo khi vừa đăng ký. */
    @Volatile private var lastSignature: String? = null

    /** Được gọi khi danh sách SIM thật sự thay đổi (để nạp lại danh bạ/lịch sử theo vùng mới). */
    @Volatile var onSimsChanged: (() -> Unit)? = null

    @Volatile private var simsCache: List<SimInfo>? = null
    @Volatile private var simsCachedAt = 0L
    @Volatile private var regionCache: String? = null
    @Volatile private var regionCachedAt = 0L

    /** Đăng ký theo dõi thay đổi SIM để xóa bộ nhớ đệm ngay khi tháo/lắp/đổi SIM. */
    fun start() {
        try {
            subs.addOnSubscriptionsChangedListener(
                ContextCompat.getMainExecutor(context),
                object : SubscriptionManager.OnSubscriptionsChangedListener() {
                    override fun onSubscriptionsChanged() {
                        // Android gọi hàm này NGAY MỘT LẦN lúc vừa đăng ký (không phải thay đổi thật), và cả khi chỉ
                        // trạng thái thuê bao đổi. Chỉ coi là thay đổi khi danh sách SIM khác lần trước.
                        val signature = loadSims().joinToString(";") { "${it.slot}:${it.subscriptionId}" }
                        val previous = lastSignature
                        lastSignature = signature
                        if (previous != null && previous != signature) {
                            Diag.i(TAG, "Danh sách SIM thay đổi ($previous -> $signature): xóa bộ nhớ đệm, nạp lại chỉ mục")
                            invalidate()
                            onSimsChanged?.invoke()
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Diag.w(TAG, "Không đăng ký theo dõi thay đổi SIM: ${e.message}")
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Xóa bộ nhớ đệm (khi đổi SIM, cấp quyền, mở lại ứng dụng). */
    fun invalidate() {
        cache.clear()
        regionByHandle.clear()
        simsCache = null
        regionCache = null
    }

    /** Khe SIM (1, 2, ...) của tài khoản gọi, hoặc null nếu không biết. */
    fun slotOf(handle: PhoneAccountHandle?): Int? {
        if (handle == null || !hasPermission()) return null
        val now = SystemClock.elapsedRealtime()
        val cached = cache[handle]
        if (cached != null && now - cached.atMs < CACHE_TTL_MS) return cached.slot
        val slot = try {
            val subId = telephony.createForPhoneAccountHandle(handle)?.subscriptionId
            if (subId == null || subId < 0) {
                null
            } else {
                subs.getActiveSubscriptionInfo(subId)?.simSlotIndex?.let { it + 1 }
            }
        } catch (e: Exception) {
            Diag.w(TAG, "Không xác định được khe SIM cho $handle: ${e.message}")
            null
        }
        // Chỉ nhớ kết quả có giá trị: lần tra thất bại (ví dụ thông tin thuê bao chưa sẵn sàng) không được nhớ, để lần sau tra lại
        // thay vì làm quy tắc theo SIM không khớp suốt cả phút.
        if (slot != null) cache[handle] = Cached(slot, now)
        return slot
    }

    fun activeSims(): List<SimInfo> {
        if (!hasPermission()) return emptyList()
        val now = SystemClock.elapsedRealtime()
        val cached = simsCache
        if (cached != null && now - simsCachedAt < SIMS_TTL_MS) return cached
        val fresh = loadSims()
        simsCache = fresh
        simsCachedAt = now
        return fresh
    }

    private fun loadSims(): List<SimInfo> {
        return try {
            subs.activeSubscriptionInfoList.orEmpty().map {
                val slot = it.simSlotIndex + 1
                SimInfo(slot, it.subscriptionId, it.displayName?.toString().orEmpty().ifBlank { "SIM $slot" })
            }.sortedBy { it.slot }
        } catch (e: Exception) {
            Diag.w(TAG, "Không đọc được danh sách SIM: ${e.message}")
            emptyList()
        }
    }

    /** Tài khoản gọi của một khe SIM, để gọi đi bằng SIM đó. */
    fun handleFor(slot: Int): PhoneAccountHandle? {
        if (!hasPermission()) return null
        return try {
            telecom.callCapablePhoneAccounts.firstOrNull { slotOf(it) == slot }
        } catch (e: Exception) {
            Diag.w(TAG, "Không lấy được tài khoản gọi cho SIM $slot: ${e.message}")
            null
        }
    }

    fun labelOf(slot: Int?): String? {
        if (slot == null) return null
        return activeSims().firstOrNull { it.slot == slot }?.label ?: "SIM $slot"
    }

    /** Mã vùng ISO để chuẩn hóa số nội địa: theo SIM, rồi theo ngôn ngữ máy, mặc định VN. */
    fun region(): String {
        val now = SystemClock.elapsedRealtime()
        val cached = regionCache
        if (cached != null && now - regionCachedAt < REGION_TTL_MS) return cached
        val fresh = loadRegion()
        regionCache = fresh
        regionCachedAt = now
        return fresh
    }

    /**
     * Mã vùng của SIM thực sự nhận cuộc gọi (theo tài khoản gọi); không xác định được thì dùng vùng mặc định của máy.
     */
    fun regionOf(handle: PhoneAccountHandle?): String {
        if (handle == null || !hasPermission()) return region()
        val now = SystemClock.elapsedRealtime()
        val cached = regionByHandle[handle]
        if (cached != null && now - cached.atMs < CACHE_TTL_MS) return cached.region
        val fromSim = try {
            telephony.createForPhoneAccountHandle(handle)?.simCountryIso?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Diag.w(TAG, "Không đọc được vùng của SIM: ${e.message}")
            null
        }
        if (fromSim == null) return region() // không nhớ kết quả dự phòng: lần sau thử đọc lại vùng của SIM
        regionByHandle[handle] = CachedRegion(fromSim, now)
        return fromSim
    }

    /**
     * Tập các vùng cần dùng khi chuẩn hóa danh bạ/lịch sử: vùng mặc định và vùng của từng SIM. Số lưu dạng nội địa trong danh bạ
     * được chuẩn hóa theo MỌI vùng này, nên cuộc gọi từ bất kỳ SIM nào cũng tra được.
     */
    fun indexRegions(): Set<String> {
        val set = LinkedHashSet<String>()
        set.add(region())
        if (hasPermission()) {
            try {
                for (handle in telecom.callCapablePhoneAccounts) set.add(regionOf(handle))
            } catch (e: Exception) {
                Diag.w(TAG, "Không lấy được vùng của các SIM: ${e.message}")
            }
        }
        return set
    }

    private fun loadRegion(): String {
        val sim = try {
            telephony.simCountryIso?.uppercase(Locale.ROOT)
        } catch (e: Exception) {
            null
        }
        if (!sim.isNullOrBlank()) return sim
        val locale = Locale.getDefault().country
        return locale.ifBlank { "VN" }
    }

    private companion object {
        const val TAG = "sim"
        const val CACHE_TTL_MS = 60_000L
        const val SIMS_TTL_MS = 30_000L
        const val REGION_TTL_MS = 60_000L
    }
}
