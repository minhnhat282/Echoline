package com.minhnhat.echoline.ui.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.minhnhat.echoline.AppGraph
import com.minhnhat.echoline.R
import com.minhnhat.echoline.data.systemService
import com.minhnhat.echoline.diag.Diag

/** Gọi đi qua Telecom, chọn SIM nếu có. */
object CallPlacer {
    fun place(context: Context, graph: AppGraph, number: String, slot: Int?) {
        val cleaned = number.trim()
        if (cleaned.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(context, R.string.err_need_call_permission, Toast.LENGTH_LONG).show()
            return
        }
        try {
            val extras = Bundle()
            val handle = slot?.let { graph.sims.handleFor(it) }
            if (slot != null && handle == null) {
                // Người dùng đã chọn SIM cụ thể mà không tìm được tài khoản gọi: dừng, không âm thầm gọi bằng SIM khác.
                Diag.w("place", "Không tìm thấy tài khoản gọi cho SIM $slot")
                Toast.makeText(context, R.string.err_sim_unavailable, Toast.LENGTH_LONG).show()
                return
            }
            if (handle != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            if (graph.emergency.isEmergencyNumber(cleaned)) graph.emergency.markEmergencyCall()
            context.systemService<TelecomManager>().placeCall(Uri.fromParts("tel", cleaned, null), extras)
        } catch (e: SecurityException) {
            Diag.e("place", "Thiếu quyền khi gọi đi", e)
            Toast.makeText(context, R.string.err_need_call_permission, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Diag.e("place", "Không gọi đi được", e)
            Toast.makeText(context, R.string.err_call_failed, Toast.LENGTH_LONG).show()
        }
    }
}
