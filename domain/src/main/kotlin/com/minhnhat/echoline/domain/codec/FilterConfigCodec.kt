package com.minhnhat.echoline.domain.codec

import com.minhnhat.echoline.domain.model.AutoAnswerSettings
import com.minhnhat.echoline.domain.model.BlockStyle
import com.minhnhat.echoline.domain.model.FilterConfig
import com.minhnhat.echoline.domain.model.FilterSettings
import com.minhnhat.echoline.domain.model.Rule
import com.minhnhat.echoline.domain.model.SimScope
import com.minhnhat.echoline.domain.number.PhoneKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Đọc/ghi [FilterConfig] ra một chuỗi JSON (tệp cấu hình của app).
 *
 * Schema:
 * - 1: chỉ có "rules"; wildcard lưu theo chữ số nội địa ("02*").            (đọc được, nâng cấp khi đọc)
 * - 2: chỉ có "rules"; wildcard lưu theo tiền tố E.164 ("+842*").          (đọc được)
 * - 3: thêm "settings" và "sim" cho từng quy tắc.                           (bản hiện tại)
 *
 * Nguyên tắc an toàn: dữ liệu không đọc được thì báo [DecodeResult.Failed] để nơi gọi GIỮ NGUYÊN tệp gốc,
 * không bao giờ coi là "danh sách rỗng" (tránh mất quy tắc âm thầm).
 */
object FilterConfigCodec {
    const val SCHEMA_VERSION = 3

    sealed interface DecodeResult {
        /** @param skipped số quy tắc bị bỏ vì không hợp lệ. Khi > 0 nơi gọi nên giữ bản sao tệp gốc. */
        data class Ok(val config: FilterConfig, val schemaVersion: Int, val skipped: Int) : DecodeResult

        data class Failed(val reason: String) : DecodeResult
    }

    fun encode(config: FilterConfig): String {
        val root = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("settings", encodeSettings(config.settings))
            put("rules", JsonArray(config.rules.map { RuleJson.encode(it, includeId = true) }))
        }
        return root.toString()
    }

    /** @param region dùng để nâng cấp wildcard schema 1 ("02*") lên tiền tố E.164. */
    fun decode(json: String, region: String): DecodeResult {
        if (json.isBlank()) return DecodeResult.Ok(FilterConfig(), SCHEMA_VERSION, skipped = 0)

        val root: JsonObject = try {
            Json.parseToJsonElement(json) as? JsonObject
                ?: return DecodeResult.Failed("gốc của tệp không phải một đối tượng JSON")
        } catch (e: Exception) {
            return DecodeResult.Failed("JSON hỏng: ${e.message}")
        }

        val array = root["rules"] as? JsonArray
            ?: return DecodeResult.Failed("thiếu danh sách \"rules\"")

        val version = root.int("schemaVersion") ?: 1

        val rules = ArrayList<Rule>(array.size)
        var skipped = 0
        for (element in array) {
            val rule = parseRule(element, region)
            if (rule == null) skipped++ else rules.add(rule)
        }
        // Có mục nhưng không mục nào hiểu được (ví dụ tên trường bị đổi): dữ liệu không nhận ra,
        // tuyệt đối không được coi là "danh sách rỗng".
        if (array.isNotEmpty() && rules.isEmpty()) {
            return DecodeResult.Failed("có ${array.size} mục nhưng không mục nào đọc được")
        }

        val settings = (root["settings"] as? JsonObject)?.let(::decodeSettings) ?: FilterSettings()
        return DecodeResult.Ok(FilterConfig(dedupe(rules), settings), version, skipped)
    }

    // ---------------------------------------------------------------- rules

    private fun parseRule(element: JsonElement, region: String): Rule? {
        return try {
            val obj = element as? JsonObject ?: return null
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            var pattern = obj.string("pattern")?.takeIf { it.isNotEmpty() } ?: return null
            val action = RuleJson.parseAction(obj.string("action")) ?: return null
            val sim = RuleJson.parseSim(obj) ?: return null

            // Nâng cấp wildcard schema 1 ("02*", chữ số nội địa) lên tiền tố E.164 theo region hiện tại.
            if (pattern.endsWith("*") && !pattern.startsWith("+")) {
                val converted = PhoneKeys.nationalPrefixToE164(
                    pattern.removeSuffix("*").filter { it.isDigit() }, region
                ) ?: return null
                pattern = "$converted*"
            }
            if (!RuleJson.isUsablePattern(pattern)) return null

            Rule(
                id = id,
                pattern = pattern,
                display = obj.string("display") ?: pattern,
                action = action,
                sim = sim,
                note = obj.string("note")?.takeIf { it.isNotBlank() },
                createdAt = obj.long("createdAt") ?: 0L
            )
        } catch (ignored: Exception) {
            null
        }
    }

    /** Trùng (pattern, sim) thì giữ mục đứng sau, giữ vị trí của mục đầu. */
    private fun dedupe(rules: List<Rule>): List<Rule> {
        val map = LinkedHashMap<Pair<String, SimScope>, Rule>()
        for (rule in rules) map[rule.pattern to rule.sim] = rule
        return map.values.toList()
    }

    // ------------------------------------------------------------- settings

    private fun encodeSettings(s: FilterSettings): JsonObject = buildJsonObject {
        put("blockHidden", s.blockHidden)
        put("blockUnknown", s.blockUnknown)
        put("blockStyle", s.blockStyle.name)
        put("emergencyGraceDays", s.emergencyGraceDays)
        put("autoAnswer", buildJsonObject {
            put("enabled", s.autoAnswer.enabled)
            put("delaySec", s.autoAnswer.delaySec)
            put("muteMic", s.autoAnswer.muteMic)
            put("hangUpAfterSec", s.autoAnswer.hangUpAfterSec)
            put("historyWindowMin", s.autoAnswer.historyWindowMin)
        })
    }

    /** Từng trường sai kiểu hoặc thiếu thì dùng mặc định; số được kẹp vào khoảng hợp lý. */
    private fun decodeSettings(obj: JsonObject): FilterSettings {
        val d = FilterSettings()
        val da = AutoAnswerSettings()
        val auto = obj["autoAnswer"] as? JsonObject
        return FilterSettings(
            blockHidden = obj.bool("blockHidden") ?: d.blockHidden,
            blockUnknown = obj.bool("blockUnknown") ?: d.blockUnknown,
            blockStyle = obj.string("blockStyle")?.trim()?.let { name ->
                BlockStyle.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            } ?: d.blockStyle,
            emergencyGraceDays = (obj.int("emergencyGraceDays") ?: d.emergencyGraceDays).coerceIn(0, 30),
            autoAnswer = AutoAnswerSettings(
                enabled = auto?.bool("enabled") ?: da.enabled,
                delaySec = (auto?.int("delaySec") ?: da.delaySec).coerceIn(0, 120),
                muteMic = auto?.bool("muteMic") ?: da.muteMic,
                hangUpAfterSec = (auto?.int("hangUpAfterSec") ?: da.hangUpAfterSec).coerceIn(1, 3600),
                historyWindowMin = (auto?.int("historyWindowMin") ?: da.historyWindowMin).coerceIn(0, 43_200)
            )
        )
    }
}
