package com.minhnhat.echoline.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
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
 * Các số có trong nhật ký cuộc gọi 30 ngày gần nhất (số -> lần gần nhất), giữ trong bộ nhớ.
 * Dùng để quyết định "số này đã từng gọi/được gọi gần đây chưa" mà không truy vấn nhật ký ở đường nóng.
 */
class RecentNumbers(
    private val context: Context,
    private val regions: () -> Set<String>,
    private val scope: CoroutineScope
) {
    @Volatile
    private var lastSeen: Map<String, Long>? = null

    /** true khi [lastSeen] đã được làm mới từ nhật ký cuộc gọi trong tiến trình này (bản chụp từ đĩa có thể cũ). */
    @Volatile
    var verified: Boolean = false
        private set

    /** Hoàn tất khi lần làm mới đầu tiên đã chạy xong (thành công hay không). */
    private val refreshAttempted = CompletableDeferred<Unit>()
    private var debounceJob: Job? = null
    private val cacheFile = File(context.filesDir, "recents-cache.txt")

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    /** true/false nếu đã nạp; null nếu chưa nạp xong. */
    fun hasRecent(keys: NumberKeys, windowMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean? {
        val map = lastSeen ?: return null
        val t = map[keys.exactKey] ?: return false
        return nowMs - t <= windowMs
    }

    /**
     * Kết quả chắc chắn, không chặn: true nếu có lần gần đây trong cửa sổ (kể cả bản chụp chưa xác minh); false CHỈ khi chỉ mục đã
     * được làm mới trong tiến trình này; null nếu chưa biết chắc.
     */
    fun hasRecentTrusted(keys: NumberKeys, windowMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean? {
        val map = lastSeen ?: return null
        val t = map[keys.exactKey]
        if (t != null && nowMs - t <= windowMs) return true
        return if (verified) false else null
    }

    /**
     * Xác minh khi [hasRecentTrusted] chưa chắc: truy vấn nhật ký cuộc gọi CHỈ trong cửa sổ cần xét (thường vài dòng), không cần
     * nạp cả 30 ngày. Không chặn luồng. Trả null nếu không xác định được.
     */
    suspend fun resolve(keys: NumberKeys, windowMs: Long): Boolean? {
        hasRecentTrusted(keys, windowMs)?.let { return it }
        val direct = withContext(Dispatchers.IO) { queryWindow(keys, windowMs) }
        if (direct != null) return direct
        refreshAttempted.await()
        return hasRecentTrusted(keys, windowMs)
    }

    private fun queryWindow(keys: NumberKeys, windowMs: Long): Boolean? {
        if (!hasPermission()) return null
        return try {
            val now = System.currentTimeMillis()
            val regionSet = regions()
            var found = false
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((now - windowMs).toString()),
                null
            )?.use { c ->
                val iNumber = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                while (!found && c.moveToNext()) {
                    val raw = c.getString(iNumber)
                    if (raw.isNullOrBlank()) continue
                    for (r in regionSet) {
                        if (PhoneKeys.keysOf(raw, r).exactKey == keys.exactKey) {
                            found = true
                            break
                        }
                    }
                }
            } ?: return null
            found
        } catch (e: Exception) {
            Diag.w(TAG, "Tra trực tiếp nhật ký cuộc gọi lỗi: ${e.message}")
            null
        }
    }

    fun start() {
        loadSnapshot()
        try {
            context.contentResolver.registerContentObserver(
                CallLog.Calls.CONTENT_URI,
                true,
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) {
                        scheduleReload()
                    }
                }
            )
        } catch (e: Exception) {
            Diag.w(TAG, "Không đăng ký theo dõi nhật ký cuộc gọi: ${e.message}")
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

    private fun loadSnapshot() {
        try {
            if (!hasPermission()) {
                cacheFile.delete()
                return
            }
            if (!cacheFile.exists()) return
            val lines = cacheFile.readLines()
            if (lines.isEmpty()) return
            if (lines[0] != regions().sorted().joinToString(",")) {
                Diag.i(TAG, "Bỏ bản chụp lịch sử: tập vùng đã đổi")
                return
            }
            val map = HashMap<String, Long>(lines.size)
            for (i in 1 until lines.size) {
                val parts = lines[i].split('\t')
                if (parts.size != 2) continue
                val t = parts[1].toLongOrNull() ?: continue
                map[parts[0]] = t
            }
            lastSeen = map
            Diag.i(TAG, "Đã nạp bản chụp lịch sử cuộc gọi: ${map.size} số")
        } catch (e: Exception) {
            Diag.w(TAG, "Không đọc được bản chụp lịch sử: ${e.message}")
        }
    }

    private fun saveSnapshot(regionCodes: String, map: Map<String, Long>) {
        try {
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.bufferedWriter().use { w ->
                w.write(regionCodes)
                w.newLine()
                for ((k, t) in map) {
                    w.write("$k\t$t")
                    w.newLine()
                }
            }
            if (!tmp.renameTo(cacheFile)) tmp.delete()
        } catch (e: Exception) {
            Diag.w(TAG, "Không ghi được bản chụp lịch sử: ${e.message}")
        }
    }

    @Synchronized
    private fun loadNow() {
        if (!hasPermission()) {
            lastSeen = null
            verified = false
            cacheFile.delete()
            Diag.i(TAG, "Chưa có quyền Nhật ký cuộc gọi: đã xóa chỉ mục và bản chụp")
            return
        }
        try {
            val started = System.currentTimeMillis()
            val regionSet = regions()
            val map = HashMap<String, Long>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf((started - HORIZON_MS).toString()),
                null
            )?.use { c ->
                val iNumber = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val iDate = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
                while (c.moveToNext()) {
                    val raw = c.getString(iNumber)
                    if (raw.isNullOrBlank()) continue
                    val date = c.getLong(iDate)
                    for (r in regionSet) {
                        val key = PhoneKeys.keysOf(raw, r).exactKey
                        val old = map[key]
                        if (old == null || date > old) map[key] = date
                    }
                }
            }
            lastSeen = map
            verified = true
            if (!refreshAttempted.isCompleted) refreshAttempted.complete(Unit)
            saveSnapshot(regionSet.sorted().joinToString(","), map)
            Diag.i(TAG, "Đã nạp ${map.size} số trong nhật ký trong ${System.currentTimeMillis() - started} ms")
        } catch (e: Exception) {
            Diag.e(TAG, "Lỗi khi nạp nhật ký cuộc gọi", e)
            if (!refreshAttempted.isCompleted) refreshAttempted.complete(Unit)
        }
    }

    private companion object {
        const val TAG = "recents"
        const val DEBOUNCE_MS = 2000L
        const val HORIZON_MS = 30L * 24 * 60 * 60 * 1000
    }
}
