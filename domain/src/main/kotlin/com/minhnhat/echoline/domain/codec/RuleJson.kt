package com.minhnhat.echoline.domain.codec

import com.minhnhat.echoline.domain.model.Rule
import com.minhnhat.echoline.domain.model.RuleAction
import com.minhnhat.echoline.domain.model.SimScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Các hàm đọc/ghi JSON dùng chung. Cố ý dùng cây JSON (không dùng phản chiếu hay lớp @Serializable theo tên trường)
 * để bản build có R8 đổi tên trường cũng không làm mất dữ liệu, và để chịu được tệp thiếu trường.
 */
internal object RuleJson {

    fun encode(rule: Rule, includeId: Boolean): JsonObject = buildJsonObject {
        if (includeId) put("id", rule.id)
        put("pattern", rule.pattern)
        put("display", rule.display)
        put("action", rule.action.name)
        put("sim", rule.sim.name)
        rule.note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
        put("createdAt", rule.createdAt)
    }

    fun parseAction(text: String?): RuleAction? {
        val name = text?.trim() ?: return null
        return RuleAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Thiếu trường (hoặc null) -> [SimScope.ANY]. Có trường nhưng không nhận ra -> null (mục bị bỏ qua,
     * không được âm thầm mở rộng thành "mọi SIM").
     */
    fun parseSim(obj: JsonObject): SimScope? {
        val raw = obj.string("sim") ?: return SimScope.ANY
        val name = raw.trim()
        return SimScope.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    /** Wildcard đã chuẩn hóa phải có dạng "+<mã quốc gia><chữ số>*", tối thiểu 2 chữ số sau dấu '+'. */
    fun isUsablePattern(pattern: String): Boolean {
        if (pattern.isEmpty()) return false
        if (!pattern.endsWith("*")) return true
        val prefix = pattern.removeSuffix("*")
        return prefix.length >= 3 && prefix.startsWith("+") && prefix.drop(1).all { it.isDigit() } &&
            prefix[1] != '0'
    }
}

internal fun JsonObject.string(key: String): String? {
    val p = this[key] as? JsonPrimitive ?: return null
    return if (p is JsonNull) null else p.content
}

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
