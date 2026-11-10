package com.minhnhat.echoline.data

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager

/**
 * Ghi nhận lần người dùng gọi số khẩn cấp gần nhất. Trong thời gian ân hạn sau đó mọi quyết định CHẶN bị hủy,
 * để không chặn nhầm cuộc gọi lại từ dịch vụ khẩn cấp.
 */
class EmergencyTracker(private val context: Context, private val prefs: AppPrefs) {

    @Suppress("DEPRECATION")
    fun isEmergencyNumber(number: String): Boolean {
        return try {
            context.systemService<TelephonyManager>().isEmergencyNumber(number)
        } catch (e: Exception) {
            try {
                PhoneNumberUtils.isEmergencyNumber(number)
            } catch (e2: Exception) {
                false
            }
        }
    }

    fun markEmergencyCall() {
        prefs.emergencyCallAt = System.currentTimeMillis()
    }

    fun graceActive(days: Int): Boolean {
        if (days <= 0) return false
        val at = prefs.emergencyCallAt
        return at > 0L && System.currentTimeMillis() - at < days * DAY_MS
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}
