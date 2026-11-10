package com.minhnhat.echoline.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.minhnhat.echoline.diag.Diag
import com.minhnhat.echoline.domain.number.NumberKeys
import com.minhnhat.echoline.domain.number.PhoneKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Chỉ mục số trong danh bạ, giữ trong bộ nhớ (tra O(1), không gọi ContactsProvider ở đường nóng).
 * Nạp ngay khi tiến trình khởi động, tự nạp lại khi danh bạ đổi.
 */
class ContactsIndex(
    private val context: Context,
    private val regions: () -> Set<String>,
    private val scope: CoroutineScope
) {
    @Volatile
    private var numbers: Set<String>? = null

    /**
     * true khi [numbers] đã được làm mới từ ContactsProvider trong tiến trình này. Bản chụp nạp từ đĩa lúc khởi động có thể cũ
     * (danh bạ đổi khi tiến trình đã bị tắt): kết quả "có trong danh bạ" vẫn tin được, nhưng kết quả "không có" thì phải xác minh.
     */
    @Volatile
    var verified: Boolean = false
        private set

    /** Hoàn tất khi lần làm mới đầu tiên từ ContactsProvider đã chạy xong (thành công hay không). */
    private val refreshAttempted = CompletableDeferred<Unit>()
    private var debounceJob: Job? = null
    private val cacheFile = File(context.filesDir, "contacts-cache.txt")

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** true/false nếu đã nạp; null nếu chưa nạp xong. */
    fun contains(keys: NumberKeys): Boolean? {
        val set = numbers ?: return null
        return keys.exactKey in set
    }

    /**
     * Kết quả chắc chắn, không chặn: true nếu số có trong chỉ mục (kể cả bản chụp chưa xác minh); false CHỈ khi chỉ mục đã được
     * làm mới trong tiến trình này; null nếu chưa biết chắc (chưa nạp, hoặc bản chụp chưa xác minh mà không thấy số).
     */
    fun containsTrusted(keys: NumberKeys): Boolean? {
        val set = numbers ?: return null
        if (keys.exactKey in set) return true
        return if (verified) false else null
    }

    /**
     * Xác minh một số khi [containsTrusted] chưa chắc: tra trực tiếp ContactsProvider cho đúng số này (nhanh, không cần nạp cả
     * danh bạ). Không chặn luồng. Trả null nếu không xác định được.
     */
    suspend fun resolve(keys: NumberKeys): Boolean? {
        containsTrusted(keys)?.let { return it }
        val direct = withContext(Dispatchers.IO) { lookupDirect(keys.exactKey) }
        if (direct != null) return direct
        // Tra trực tiếp lỗi: chờ lần làm mới đầy đủ rồi dùng chỉ mục (null nếu lần làm mới cũng lỗi).
        refreshAttempted.await()
        return containsTrusted(keys)
    }

    private fun lookupDirect(number: String): Boolean? {
        if (!hasPermission() || number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() }
        } catch (e: Exception) {
            Diag.w(TAG, "Tra trực tiếp danh bạ lỗi: ${e.message}")
            null
        }
    }

    fun start() {
        loadSnapshot()
        try {
            context.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        scheduleReload()
                    }
                }
            )
        } catch (e: Exception) {
            Diag.w(TAG, "Không đăng ký theo dõi danh bạ: ${e.message}")
        }
        reload()
    }

    fun reload() {
        scope.launch(Dispatchers.IO) { loadNow() }
    }

    private fun scheduleReload() {
        debounceJob?.cancel()
        debounceJob = scope.launch(Dispatchers.IO) {
            delay(DEBOUNCE_MS)
            loadNow()
        }
    }

    /**
     * Nạp bản chụp lần trước từ đĩa (rất nhanh, không phân tích số), để cuộc gọi đầu tiên sau khi tiến trình khởi động lại
     * không phải chờ nạp danh bạ. Bản chụp sẽ được làm mới ngay sau đó.
     */
    private fun loadSnapshot() {
        try {
            if (!hasPermission()) {
                cacheFile.delete()
                return
            }
            if (!cacheFile.exists()) return
            val lines = cacheFile.readLines()
            if (lines.isEmpty()) return
            // Dòng đầu là danh sách vùng đã dùng để chuẩn hóa. Khác với hiện tại (đổi SIM lúc tiến trình đã tắt) thì khóa trong
            // bản chụp không còn phù hợp: bỏ, để các cuộc gọi đến trước khi nạp xong tra trực tiếp.
            if (lines[0] != regions().sorted().joinToString(",")) {
                Diag.i(TAG, "Bỏ bản chụp danh bạ: tập vùng đã đổi")
                return
            }
            val set = HashSet<String>(lines.size)
            for (i in 1 until lines.size) if (lines[i].isNotEmpty()) set.add(lines[i])
            numbers = set
            Diag.i(TAG, "Đã nạp bản chụp danh bạ: ${set.size} số")
        } catch (e: Exception) {
            Diag.w(TAG, "Không đọc được bản chụp danh bạ: ${e.message}")
        }
    }

    private fun saveSnapshot(regionCodes: String, set: Set<String>) {
        try {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write(regionCodes)
                w.newLine()
                for (k in set) {
                    w.write(k)
                    w.newLine()
                }
            }
            if (!tmp.renameTo(cacheFile)) tmp.delete()
        } catch (e: Exception) {
            Diag.w(TAG, "Không ghi được bản chụp danh bạ: ${e.message}")
        }
    }

    @Synchronized
    private fun loadNow() {
        if (!hasPermission()) {
            // Không giữ dữ liệu dẫn xuất từ danh bạ khi không còn quyền.
            numbers = null
            verified = false
            cacheFile.delete()
            Diag.i(TAG, "Chưa có quyền Danh bạ: đã xóa chỉ mục và bản chụp")
            return
        }
        try {
            val started = System.currentTimeMillis()
            val regionSet = regions()
            val set = HashSet<String>()
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null
            )?.use { c ->
                val idx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val raw = c.getString(idx)
                    if (raw.isNullOrBlank()) continue
                    for (r in regionSet) set.add(PhoneKeys.keysOf(raw, r).exactKey)
                }
            }
            numbers = set
            verified = true
            if (!refreshAttempted.isCompleted) refreshAttempted.complete(Unit)
            saveSnapshot(regionSet.sorted().joinToString(","), set)
            Diag.i(TAG, "Đã nạp ${set.size} số danh bạ trong ${System.currentTimeMillis() - started} ms")
        } catch (e: Exception) {
            Diag.e(TAG, "Lỗi khi nạp danh bạ", e)
            // Không để các cuộc gọi sau phải chờ mãi một lần nạp đã lỗi: contains() sẽ trả null và LiveFacts báo "danh bạ không nạp được".
            if (!refreshAttempted.isCompleted) refreshAttempted.complete(Unit)
        }
    }

    /** Tên hiển thị của một số (truy vấn trực tiếp, chỉ gọi ở luồng nền); null nếu không có. */
    fun displayName(number: String): String? {
        if (!hasPermission() || number.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            Diag.w(TAG, "Tra tên danh bạ lỗi: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "contacts"
        const val DEBOUNCE_MS = 1500L
    }
}
