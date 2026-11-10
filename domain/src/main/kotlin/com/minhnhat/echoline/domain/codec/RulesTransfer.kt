package com.minhnhat.echoline.domain.codec

import com.minhnhat.echoline.domain.model.Rule
import com.minhnhat.echoline.domain.model.SimScope
import com.minhnhat.echoline.domain.number.PatternParseResult
import com.minhnhat.echoline.domain.number.PatternParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Kết quả đọc một tệp quy tắc đã xuất. */
sealed interface TransferParseResult {
    /** @param skipped số mục bị bỏ qua vì không hợp lệ. */
    data class Ok(val rules: List<Rule>, val skipped: Int) : TransferParseResult

    /** Tệp không phải là tệp quy tắc (hoặc quá lớn/hỏng). */
    data object Invalid : TransferParseResult
}

/**
 * Xuất / nhập quy tắc bằng tệp JSON để chia sẻ hoặc sao lưu.
 *
 * Đọc được tệp xuất của bản đầu (định dạng "mndialer-call-rules"): wildcard dạng nội địa ("02*") được chuẩn hóa
 * khi nhập, tên hành động không phân biệt hoa thường, mục sai bị bỏ qua (không làm hỏng cả tệp),
 * trùng (pattern, sim) thì giữ một.
 */
object RulesTransfer {
    const val MAX_RULES = 5000
    const val MAX_CHARS = 4 * 1024 * 1024
    const val FORMAT_TYPE = "echoline-call-rules"
    const val FORMAT_VERSION = 1

    private val pretty = Json { prettyPrint = true }

    fun export(rules: List<Rule>, now: Long): String {
        val root = buildJsonObject {
            put("type", FORMAT_TYPE)
            put("schemaVersion", FORMAT_VERSION)
            put("exportedAt", now)
            put("rules", JsonArray(rules.sortedBy { it.createdAt }.map { RuleJson.encode(it, includeId = false) }))
        }
        return pretty.encodeToString(JsonElement.serializer(), root)
    }

    /** Số cụ thể được chuẩn hóa lại theo [region] của máy nhập. */
    fun parse(json: String, region: String, now: Long): TransferParseResult {
        if (json.length > MAX_CHARS) return TransferParseResult.Invalid

        val array: JsonArray = try {
            when (val root = Json.parseToJsonElement(json)) {
                is JsonArray -> root
                is JsonObject -> root["rules"] as? JsonArray ?: return TransferParseResult.Invalid
                else -> return TransferParseResult.Invalid
            }
        } catch (ignored: Exception) {
            return TransferParseResult.Invalid
        }
        if (array.size > MAX_RULES) return TransferParseResult.Invalid

        val result = LinkedHashMap<Pair<String, SimScope>, Rule>()
        var skipped = 0
        for (element in array) {
            val rule = parseRule(element, region, now)
            if (rule == null) skipped++ else result[rule.pattern to rule.sim] = rule
        }
        return TransferParseResult.Ok(result.values.toList(), skipped)
    }

    private fun parseRule(element: JsonElement, region: String, now: Long): Rule? {
        return try {
            val obj = element as? JsonObject ?: return null
            val action = RuleJson.parseAction(obj.string("action")) ?: return null
            val sim = RuleJson.parseSim(obj) ?: return null
            val display = obj.string("display")
            val source = obj.string("pattern") ?: display ?: return null
            val parsed = PatternParser.parse(source, region) as? PatternParseResult.Valid ?: return null
            Rule(
                id = UUID.randomUUID().toString(),
                pattern = parsed.pattern,
                display = display?.takeIf { it.isNotBlank() } ?: parsed.display,
                action = action,
                sim = sim,
                note = obj.string("note")?.trim()?.takeIf { it.isNotEmpty() },
                createdAt = obj.long("createdAt") ?: now
            )
        } catch (ignored: Exception) {
            null
        }
    }
}
