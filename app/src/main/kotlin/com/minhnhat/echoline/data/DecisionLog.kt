package com.minhnhat.echoline.data

import android.content.Context
import android.util.AtomicFile
import com.minhnhat.echoline.diag.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Một dòng nhật ký quyết định.
 * @param decision mã: RING, BLOCK, SILENCE, AUTO_HIDDEN, AUTO_UI.
 * @param source nơi quyết định: screening-sync, screening-async, screening-timeout, incall-fallback.
 * @param note việc đã làm thêm sau khi hệ thống đã được trả lời (áp dụng bù, không tự trả lời được...).
 */
data class DecisionEntry(
    val timeMs: Long,
    val number: String?,
    val simSlot: Int?,
    val decision: String,
    val reason: String,
    val elapsedMs: Long,
    val source: String,
    val note: String? = null
)

/** 60 dòng quyết định gần nhất, lưu cả vào tệp để xem lại sau khi tiến trình bị tắt. */
class DecisionLog(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "decision-log.json")
    private val _entries = MutableStateFlow<List<DecisionEntry>>(emptyList())
    val entries: StateFlow<List<DecisionEntry>> = _entries.asStateFlow()
    private var saveJob: Job? = null

    /** Tăng mỗi lần xóa nhật ký; lần nạp từ đĩa bắt đầu trước khi xóa thì không được ghi lại dữ liệu cũ. */
    @Volatile private var generation = 0

    init {
        scope.launch(Dispatchers.IO) { load() }
    }

    fun add(entry: DecisionEntry) {
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
        scheduleSave()
    }

    /** Thêm ghi chú vào dòng gần nhất khớp (số, thời điểm) — dùng khi áp dụng bù sau khi đã ghi nhật ký. */
    fun annotate(timeMs: Long, note: String) {
        _entries.update { list ->
            list.map { if (it.timeMs == timeMs) it.copy(note = listOfNotNull(it.note, note).joinToString("; ")) else it }
        }
        scheduleSave()
    }

    fun clear() {
        generation++
        _entries.value = emptyList()
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(SAVE_DELAY_MS)
            save(_entries.value)
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val startedGeneration = generation
            val array = Json.parseToJsonElement(file.readText()) as? JsonArray ?: return
            val loaded = array.mapNotNull(::decode)
            // Hợp nhất với các dòng đã được thêm trong lúc đang nạp (không ghi đè dòng mới). Nếu người dùng đã xóa nhật ký
            // trong lúc nạp thì bỏ dữ liệu cũ.
            _entries.update { current ->
                if (generation != startedGeneration) current
                else (current + loaded).sortedByDescending { it.timeMs }.take(MAX_ENTRIES)
            }
        } catch (e: Exception) {
            Diag.w(TAG, "Không đọc được nhật ký quyết định: ${e.message}")
        }
    }

    private fun save(list: List<DecisionEntry>) {
        try {
            val json = JsonArray(list.map(::encode)).toString()
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            try {
                out.write(json.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                throw e
            }
        } catch (e: Exception) {
            Diag.w(TAG, "Không ghi được nhật ký quyết định: ${e.message}")
        }
    }

    private fun encode(e: DecisionEntry): JsonObject = buildJsonObject {
        put("t", e.timeMs)
        e.number?.let { put("n", it) }
        e.simSlot?.let { put("s", it) }
        put("d", e.decision)
        put("r", e.reason)
        put("e", e.elapsedMs)
        put("src", e.source)
        e.note?.let { put("note", it) }
    }

    private fun decode(element: JsonElement): DecisionEntry? {
        val o = element as? JsonObject ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val time = (o["t"] as? JsonPrimitive)?.longOrNull ?: return null
        return DecisionEntry(
            timeMs = time,
            number = str("n"),
            simSlot = (o["s"] as? JsonPrimitive)?.intOrNull,
            decision = str("d") ?: return null,
            reason = str("r").orEmpty(),
            elapsedMs = (o["e"] as? JsonPrimitive)?.longOrNull ?: 0L,
            source = str("src").orEmpty(),
            note = str("note")
        )
    }

    private companion object {
        const val TAG = "decisionlog"
        const val MAX_ENTRIES = 60
        const val SAVE_DELAY_MS = 800L
    }
}
