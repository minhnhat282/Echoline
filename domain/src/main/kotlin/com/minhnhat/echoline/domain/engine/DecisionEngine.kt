package com.minhnhat.echoline.domain.engine

import com.minhnhat.echoline.domain.model.CallInfo
import com.minhnhat.echoline.domain.model.Decision
import com.minhnhat.echoline.domain.model.DecisionResult
import com.minhnhat.echoline.domain.model.FilterConfig
import com.minhnhat.echoline.domain.model.FilterSettings
import com.minhnhat.echoline.domain.model.Rule
import com.minhnhat.echoline.domain.model.RuleAction
import com.minhnhat.echoline.domain.number.NumberKeys
import com.minhnhat.echoline.domain.number.PhoneKeys

/**
 * Thứ tự ưu tiên (dừng ở bước khớp đầu tiên). Mọi lỗi đều dẫn tới "đổ chuông bình thường".
 *
 * 1. Không có số (số ẩn): theo công tắc "chặn số ẩn".
 * 2. Quy tắc số cụ thể khớp: áp dụng, KỂ CẢ khi số có trong danh bạ.
 * 3. Wildcard khớp (tiền tố dài nhất): áp dụng, trừ khi số có trong danh bạ (thì đổ chuông).
 * 4. Không khớp quy tắc nào: nếu bật "chặn số lạ" thì chặn (trừ số trong danh bạ), ngược lại đổ chuông.
 * 5. Lớp ngoài cùng: mọi quyết định CHẶN bị hủy nếu đang trong ân hạn sau cuộc gọi khẩn cấp.
 *
 * Danh bạ chỉ được tra khi quyết định sắp áp dụng khác "đổ chuông": kết quả giống hệt việc tra trước,
 * vì số trong danh bạ luôn đổ chuông.
 */
object DecisionEngine {

    fun evaluate(call: CallInfo, config: FilterConfig, facts: Facts): DecisionResult {
        return try {
            val result = evaluateInternal(call, config, facts)
            if (result.decision is Decision.Block && emergencyGraceActive(facts)) {
                DecisionResult(
                    Decision.Ring,
                    "vừa gọi số khẩn cấp: tạm không chặn (${result.reason})",
                    result.ruleId
                )
            } else {
                result
            }
        } catch (e: ContactsUnavailableException) {
            // Không biết ai là người quen: không dám áp dụng mẫu có dấu * hay "chặn số lạ".
            // Quy tắc số cụ thể ở bước 2 đã được xét trước đó.
            DecisionResult(
                Decision.Ring,
                "không đọc được danh bạ (${e.message}): không áp dụng mẫu * và chặn số lạ -> đổ chuông"
            )
        } catch (e: Exception) {
            DecisionResult(
                Decision.Ring,
                "lỗi khi đánh giá (${e.javaClass.simpleName}: ${e.message}) -> đổ chuông"
            )
        }
    }

    private fun emergencyGraceActive(facts: Facts): Boolean =
        try {
            facts.emergencyGraceActive()
        } catch (ignored: Exception) {
            false
        }

    /** Tính một lần, dùng lại. */
    private class LazyFlag(private val compute: () -> Boolean) {
        private var value: Boolean? = null
        fun get(): Boolean = value ?: compute().also { value = it }
    }

    private fun evaluateInternal(call: CallInfo, config: FilterConfig, facts: Facts): DecisionResult {
        val settings = config.settings
        val number = call.number

        if (number.isNullOrBlank()) {
            return if (settings.blockHidden) {
                DecisionResult(Decision.Block(settings.blockStyle), "số ẩn, đang bật chặn số ẩn")
            } else {
                DecisionResult(Decision.Ring, "số ẩn, không chặn")
            }
        }

        val keys = PhoneKeys.keysOf(number, call.region)
        val index = config.index

        index.findExact(keys, call.simSlot)?.let { rule ->
            return applyRule(rule, keys, settings, facts, contact = null)
        }

        val contact = LazyFlag { facts.isContact(keys) }

        index.findWildcard(keys, call.simSlot)?.let { rule ->
            return applyRule(rule, keys, settings, facts, contact)
        }

        if (settings.blockUnknown) {
            return if (contact.get()) {
                DecisionResult(Decision.Ring, "có trong danh bạ")
            } else {
                DecisionResult(
                    Decision.Block(settings.blockStyle),
                    "số lạ (không có trong danh bạ), đang bật chặn số lạ"
                )
            }
        }
        return DecisionResult(Decision.Ring, "không khớp quy tắc nào")
    }

    /**
     * @param contact null với quy tắc số cụ thể (áp dụng kể cả khi có trong danh bạ); khác null với wildcard
     *        (số trong danh bạ thì bỏ qua wildcard).
     */
    private fun applyRule(
        rule: Rule,
        keys: NumberKeys,
        settings: FilterSettings,
        facts: Facts,
        contact: LazyFlag?
    ): DecisionResult {
        val ruleText = "quy tắc '${rule.display}'"
        val viaWildcard = contact != null
        val id = rule.id

        return when (rule.action) {
            RuleAction.ALLOW -> DecisionResult(Decision.Ring, "$ruleText: cho phép", id)

            RuleAction.BLOCK ->
                if (contact?.get() == true) {
                    DecisionResult(Decision.Ring, "có trong danh bạ", id)
                } else {
                    DecisionResult(Decision.Block(settings.blockStyle), "$ruleText: chặn", id)
                }

            RuleAction.AUTO_ANSWER -> {
                val auto = settings.autoAnswer
                when {
                    !auto.enabled ->
                        DecisionResult(Decision.Ring, "$ruleText: tự trả lời đang tắt", id)

                    facts.hasOtherLiveCall() ->
                        DecisionResult(Decision.Ring, "$ruleText: đang có cuộc gọi khác", id)

                    !facts.hasPrivateRoute() ->
                        DecisionResult(Decision.Ring, "$ruleText: không có đường âm thanh riêng tư (đang dùng Bluetooth hoặc không có loa trong/tai nghe có dây)", id)

                    contact?.get() == true ->
                        DecisionResult(Decision.Ring, "có trong danh bạ", id)

                    viaWildcard && auto.historyWindowMin > 0 &&
                        facts.hasRecentHistory(keys, auto.historyWindowMin * 60_000L) ->
                        DecisionResult(
                            Decision.Ring,
                            "$ruleText: số đã có trong lịch sử cuộc gọi (${auto.historyWindowMin} phút gần đây)",
                            id
                        )

                    else ->
                        DecisionResult(Decision.AutoAnswer(auto.delaySec), "$ruleText: tự trả lời", id)
                }
            }
        }
    }
}
