package com.minhnhat.echoline.data

import com.minhnhat.echoline.domain.model.FilterConfig
import com.minhnhat.echoline.domain.model.Rule
import com.minhnhat.echoline.domain.model.RuleAction
import com.minhnhat.echoline.domain.model.SimScope
import com.minhnhat.echoline.domain.number.PatternParseResult
import com.minhnhat.echoline.domain.number.PatternParser
import java.util.UUID

/** Thêm hoặc sửa một quy tắc: thay mục cùng id, và loại mục khác trùng (mẫu, SIM). */
fun FilterConfig.upsert(rule: Rule): FilterConfig {
    val duplicateIds = rules.filter { it.pattern == rule.pattern && it.sim == rule.sim }.map { it.id }.toSet() - rule.id
    val cleaned = rules.filter { it.id !in duplicateIds }
    val index = cleaned.indexOfFirst { it.id == rule.id }
    val result = if (index >= 0) cleaned.toMutableList().also { it[index] = rule } else cleaned + rule
    return copy(rules = result)
}

fun FilterConfig.removeRule(id: String): FilterConfig = copy(rules = rules.filterNot { it.id == id })

/** Tạo quy tắc cho một số cụ thể từ chuỗi số; null nếu số không hợp lệ. */
fun ruleForNumber(number: String, action: RuleAction, region: String, sim: SimScope = SimScope.ANY): Rule? {
    val parsed = PatternParser.parse(number, region) as? PatternParseResult.Valid ?: return null
    return Rule(
        id = UUID.randomUUID().toString(),
        pattern = parsed.pattern,
        display = parsed.display,
        action = action,
        sim = sim,
        note = null,
        createdAt = System.currentTimeMillis()
    )
}
