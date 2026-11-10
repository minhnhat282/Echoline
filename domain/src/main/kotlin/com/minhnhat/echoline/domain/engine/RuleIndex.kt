package com.minhnhat.echoline.domain.engine

import com.minhnhat.echoline.domain.model.Rule
import com.minhnhat.echoline.domain.model.SimScope
import com.minhnhat.echoline.domain.number.NumberKeys

/**
 * Chỉ mục tra quy tắc nhanh, độ phức tạp theo độ dài số (khoảng 15 lần tra HashMap), không phụ thuộc số quy tắc:
 * - số cụ thể: tra theo exactKey (E.164),
 * - wildcard: tiền tố E.164 ("+842"), thử từ tiền tố dài nhất xuống ngắn nhất.
 *
 * Cùng một mẫu có thể có nhiều quy tắc khác phạm vi SIM. Quy tắc theo đúng SIM của cuộc gọi thắng quy tắc [SimScope.ANY].
 * Nếu ở một độ dài tiền tố không có quy tắc nào hợp với SIM của cuộc gọi thì thử tiền tố ngắn hơn.
 */
class RuleIndex(rules: List<Rule>) {

    private val exact: Map<String, List<Rule>> =
        rules.filter { !it.isWildcard }.groupBy { it.pattern }

    private val wildcards: Map<String, List<Rule>> =
        rules
            .filter { it.isWildcard && it.prefix.startsWith("+") && it.prefix.length >= MIN_PREFIX_LENGTH }
            .groupBy { it.prefix }

    fun findExact(keys: NumberKeys, simSlot: Int?): Rule? =
        exact[keys.exactKey]?.let { pick(it, simSlot) }

    fun findWildcard(keys: NumberKeys, simSlot: Int?): Rule? {
        val e164 = keys.exactKey
        // Số không parse được thì exactKey chỉ là chữ số (không có '+'): không so khớp wildcard.
        if (wildcards.isEmpty() || !e164.startsWith("+")) return null
        var length = e164.length
        while (length >= MIN_PREFIX_LENGTH) {
            val candidates = wildcards[e164.substring(0, length)]
            if (candidates != null) {
                val rule = pick(candidates, simSlot)
                if (rule != null) return rule
            }
            length--
        }
        return null
    }

    private fun pick(candidates: List<Rule>, simSlot: Int?): Rule? {
        val scope = SimScope.forSlot(simSlot)
        if (scope != null) {
            candidates.firstOrNull { it.sim == scope }?.let { return it }
        }
        return candidates.firstOrNull { it.sim == SimScope.ANY }
    }

    private companion object {
        /** "+" cộng ít nhất 2 chữ số. */
        const val MIN_PREFIX_LENGTH = 3
    }
}
