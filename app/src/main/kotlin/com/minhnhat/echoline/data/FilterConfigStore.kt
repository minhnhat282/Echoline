package com.minhnhat.echoline.data

import android.content.Context
import android.util.AtomicFile
import com.minhnhat.echoline.diag.Diag
import com.minhnhat.echoline.domain.codec.FilterConfigCodec
import com.minhnhat.echoline.domain.model.FilterConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Nguồn sự thật duy nhất của quy tắc và cài đặt lọc: một [FilterConfig] bất biến trong một StateFlow.
 * Đường nóng chỉ đọc `config.value`. Mọi thay đổi đi qua [update] (tuần tự hóa), ghi tệp nguyên tử rồi mới công bố.
 *
 * An toàn dữ liệu: nếu tệp hỏng hoặc có mục bị bỏ, bản sao tệp gốc được lưu cạnh nó trước khi có bất kỳ lần ghi nào.
 */
class FilterConfigStore(
    private val context: Context,
    private val region: () -> String
) {
    private val file = File(context.filesDir, "filter-config.json")
    private val mutex = Mutex()

    private val _config = MutableStateFlow(FilterConfig())
    val config: StateFlow<FilterConfig> = _config.asStateFlow()

    private val _problem = MutableStateFlow<String?>(null)

    /** Thông báo vấn đề dữ liệu (tệp hỏng, mục bị bỏ, ghi lỗi) để hiện cho người dùng; null nếu bình thường. */
    val problem: StateFlow<String?> = _problem.asStateFlow()

    /** Gọi một lần lúc khởi động, đồng bộ (tệp nhỏ) để cấu hình sẵn sàng trước cuộc gọi đầu tiên. */
    fun loadBlocking() {
        try {
            if (!file.exists()) return
            val text = file.readText()
            when (val result = FilterConfigCodec.decode(text, region())) {
                is FilterConfigCodec.DecodeResult.Ok -> {
                    _config.value = result.config
                    result.config.index // dựng chỉ mục ngay, không để đến lần lọc đầu tiên
                    if (result.skipped > 0) {
                        val name = backup(text, "skipped")
                        _problem.value = "Đã bỏ qua ${result.skipped} quy tắc không hợp lệ khi đọc. Bản gốc lưu ở $name"
                        Diag.w(TAG, "Bỏ qua ${result.skipped} quy tắc; bản gốc lưu ở $name")
                    }
                    Diag.i(TAG, "Đã nạp ${result.config.rules.size} quy tắc (schema ${result.schemaVersion})")
                }
                is FilterConfigCodec.DecodeResult.Failed -> {
                    val name = backup(text, "corrupt")
                    _problem.value = "Tệp cấu hình không đọc được (${result.reason}). Bản gốc lưu ở $name. Đang dùng cấu hình trống."
                    Diag.e(TAG, "Tệp cấu hình hỏng: ${result.reason}; bản gốc lưu ở $name")
                }
            }
        } catch (e: Exception) {
            Diag.e(TAG, "Lỗi khi nạp cấu hình", e)
            _problem.value = "Lỗi khi đọc cấu hình: ${e.message}"
        }
    }

    suspend fun update(transform: (FilterConfig) -> FilterConfig) {
        mutex.withLock {
            val next = transform(_config.value)
            if (next == _config.value) return@withLock
            val error = withContext(Dispatchers.IO) {
                try {
                    next.index // dựng chỉ mục ở luồng nền trước khi công bố
                    write(FilterConfigCodec.encode(next))
                    null
                } catch (e: Exception) {
                    Diag.e(TAG, "Không ghi được tệp cấu hình", e)
                    e.message ?: e.javaClass.simpleName
                }
            }
            _config.value = next
            _problem.value = error?.let { "Không ghi được tệp cấu hình: $it. Thay đổi chỉ có hiệu lực đến khi ứng dụng khởi động lại." }
        }
    }

    fun dismissProblem() {
        _problem.value = null
    }

    private fun write(json: String) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(json.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    /**
     * Lưu bản sao tệp gốc cạnh nó. Nếu đã có bản sao cùng loại và cùng dung lượng (tức cùng nội dung, do lần khởi động trước tạo
     * khi người dùng chưa sửa gì) thì dùng lại, không tạo thêm: hàm này chạy ở luồng chính trên đường của cuộc gọi đầu tiên.
     */
    private fun backup(text: String, kind: String): String {
        val prefix = "filter-config.$kind-"
        try {
            val size = text.toByteArray(Charsets.UTF_8).size.toLong()
            val existing = context.filesDir.listFiles { f -> f.name.startsWith(prefix) && f.length() == size }
                ?.minByOrNull { it.name }
            if (existing != null) return existing.name
        } catch (e: Exception) {
            Diag.w(TAG, "Không tìm được bản sao cũ: ${e.message}")
        }
        val name = "$prefix${System.currentTimeMillis()}.json"
        try {
            File(context.filesDir, name).writeText(text)
        } catch (e: Exception) {
            Diag.e(TAG, "Không lưu được bản sao $name", e)
        }
        return name
    }

    private companion object {
        const val TAG = "config"
    }
}
