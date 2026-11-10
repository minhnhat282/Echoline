package com.minhnhat.echoline.data

import android.content.Context
import android.provider.BlockedNumberContract
import com.minhnhat.echoline.diag.Diag

/** Đọc MỘT LẦN danh sách số bị chặn của hệ thống (chỉ đọc, không ghi lại). Cần là ứng dụng điện thoại mặc định. */
object SystemBlockedImporter {
    fun read(context: Context): List<String>? {
        return try {
            val out = ArrayList<String>()
            context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0)
                    if (!n.isNullOrBlank()) out.add(n)
                }
            }
            out
        } catch (e: SecurityException) {
            Diag.w("blocked-import", "Không có quyền đọc danh sách chặn của hệ thống (cần là ứng dụng điện thoại mặc định)")
            null
        } catch (e: Exception) {
            Diag.e("blocked-import", "Lỗi khi đọc danh sách chặn của hệ thống", e)
            null
        }
    }
}
