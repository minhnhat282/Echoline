package com.minhnhat.echoline.domain.engine

import com.minhnhat.echoline.domain.model.AutoAnswerSettings
import com.minhnhat.echoline.domain.model.BlockStyle
import com.minhnhat.echoline.domain.model.CallInfo
import com.minhnhat.echoline.domain.model.Decision
import com.minhnhat.echoline.domain.model.FilterConfig
import com.minhnhat.echoline.domain.model.FilterSettings
import com.minhnhat.echoline.domain.model.Rule
import com.minhnhat.echoline.domain.model.RuleAction
import com.minhnhat.echoline.domain.model.SimScope
import com.minhnhat.echoline.domain.number.NumberKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Facts giả có đếm số lần hỏi, để kiểm tra tính lười. */
internal class FakeFacts(
    val contacts: Set<String> = emptySet(),
    val history: Set<String> = emptySet(),
    var otherLiveCall: Boolean = false,
    var privateRoute: Boolean = true,
    var emergencyGrace: Boolean = false
) : Facts {
    var contactsUnavailable = false
    var failOnOtherLiveCall = false
    var failOnEmergency = false

    var contactLookups = 0
    var historyLookups = 0
    var lastHistoryWindowMs = -1L

    override fun isContact(keys: NumberKeys): Boolean {
        contactLookups++
        if (contactsUnavailable) throw ContactsUnavailableException("thiếu quyền")
        return keys.exactKey in contacts
    }

    override fun hasOtherLiveCall(): Boolean {
        if (failOnOtherLiveCall) throw IllegalStateException("hỏng")
        return otherLiveCall
    }

    override fun hasPrivateRoute(): Boolean = privateRoute

    override fun hasRecentHistory(keys: NumberKeys, windowMs: Long): Boolean {
        historyLookups++
        lastHistoryWindowMs = windowMs
        return keys.exactKey in history
    }

    override fun emergencyGraceActive(): Boolean {
        if (failOnEmergency) throw IllegalStateException("hỏng")
        return emergencyGrace
    }
}

class DecisionEngineTest {

    private companion object {
        const val MOBILE = "0981234567"          // +84981234567
        const val MOBILE_E164 = "+84981234567"
        const val HANOI = "024 7300 1234"        // +842473001234
        const val HANOI_E164 = "+842473001234"
        const val HANOI_3800 = "0243800 1234"    // +842438001234

        val REJECT = Decision.Block(BlockStyle.REJECT)
    }

    private fun rule(pattern: String, action: RuleAction, sim: SimScope = SimScope.ANY) =
        Rule(id = "id:$pattern:$sim", pattern = pattern, display = pattern, action = action, sim = sim)

    private fun cfg(vararg rules: Rule, settings: FilterSettings = FilterSettings()) =
        FilterConfig(rules.toList(), settings)

    private fun call(number: String?, slot: Int? = 1) = CallInfo(number, slot, "VN")

    private class Case(
        val name: String,
        val config: FilterConfig,
        val facts: FakeFacts,
        val call: CallInfo,
        val expected: Decision
    )

    private val unknownBlocked = FilterSettings(blockUnknown = true)
    private val autoAnswer5s = FilterSettings(autoAnswer = AutoAnswerSettings(delaySec = 5))

    @Test
    fun `bang quyet dinh co ban`() {
        val cases = listOf(
            // --- số ẩn
            Case("số ẩn, mặc định không chặn", cfg(), FakeFacts(), call(null), Decision.Ring),
            Case("số ẩn rỗng, mặc định không chặn", cfg(), FakeFacts(), call("   "), Decision.Ring),
            Case("số ẩn, bật chặn", cfg(settings = FilterSettings(blockHidden = true)), FakeFacts(), call(null), REJECT),
            Case(
                "số ẩn, bật chặn kiểu silence",
                cfg(settings = FilterSettings(blockHidden = true, blockStyle = BlockStyle.SILENCE)),
                FakeFacts(), call(null), Decision.Block(BlockStyle.SILENCE)
            ),

            // --- số cụ thể
            Case("số cụ thể BLOCK", cfg(rule(MOBILE_E164, RuleAction.BLOCK)), FakeFacts(), call(MOBILE), REJECT),
            Case(
                "số cụ thể BLOCK thắng danh bạ",
                cfg(rule(MOBILE_E164, RuleAction.BLOCK)),
                FakeFacts(contacts = setOf(MOBILE_E164)), call(MOBILE), REJECT
            ),
            Case(
                "số cụ thể ALLOW thắng chặn số lạ",
                cfg(rule(MOBILE_E164, RuleAction.ALLOW), settings = unknownBlocked),
                FakeFacts(), call(MOBILE), Decision.Ring
            ),
            Case(
                "số cụ thể ghi dạng khác vẫn khớp",
                cfg(rule(HANOI_E164, RuleAction.BLOCK)), FakeFacts(), call("+84 24 7300 1234"), REJECT
            ),

            // --- wildcard
            Case("wildcard BLOCK, số lạ", cfg(rule("+842*", RuleAction.BLOCK)), FakeFacts(), call(HANOI), REJECT),
            Case(
                "wildcard BLOCK, số trong danh bạ thì đổ chuông",
                cfg(rule("+842*", RuleAction.BLOCK)),
                FakeFacts(contacts = setOf(HANOI_E164)), call(HANOI), Decision.Ring
            ),
            Case(
                "wildcard dài ALLOW thắng wildcard ngắn BLOCK",
                cfg(rule("+842*", RuleAction.BLOCK), rule("+84243800*", RuleAction.ALLOW)),
                FakeFacts(), call(HANOI_3800), Decision.Ring
            ),
            Case(
                "wildcard ngắn vẫn chặn số không thuộc wildcard dài",
                cfg(rule("+842*", RuleAction.BLOCK), rule("+84243800*", RuleAction.ALLOW)),
                FakeFacts(), call(HANOI), REJECT
            ),
            Case(
                "số cụ thể ALLOW thắng wildcard BLOCK",
                cfg(rule("+842*", RuleAction.BLOCK), rule(HANOI_E164, RuleAction.ALLOW)),
                FakeFacts(), call(HANOI), Decision.Ring
            ),
            Case("không khớp quy tắc nào", cfg(rule("+842*", RuleAction.BLOCK)), FakeFacts(), call(MOBILE), Decision.Ring),

            // --- chặn số lạ
            Case("chặn số lạ: số lạ bị chặn", cfg(settings = unknownBlocked), FakeFacts(), call(MOBILE), REJECT),
            Case(
                "chặn số lạ: số trong danh bạ đổ chuông",
                cfg(settings = unknownBlocked),
                FakeFacts(contacts = setOf(MOBILE_E164)), call(MOBILE), Decision.Ring
            ),
            Case(
                "chặn số lạ: wildcard ALLOW cho qua số lạ",
                cfg(rule("+8498*", RuleAction.ALLOW), settings = unknownBlocked),
                FakeFacts(), call(MOBILE), Decision.Ring
            ),

            // --- SIM
            Case(
                "quy tắc SIM 2: cuộc gọi SIM 2 bị chặn",
                cfg(rule(MOBILE_E164, RuleAction.BLOCK, SimScope.SLOT_2)), FakeFacts(), call(MOBILE, 2), REJECT
            ),
            Case(
                "quy tắc SIM 2: cuộc gọi SIM 1 đổ chuông",
                cfg(rule(MOBILE_E164, RuleAction.BLOCK, SimScope.SLOT_2)), FakeFacts(), call(MOBILE, 1), Decision.Ring
            ),
            Case(
                "quy tắc SIM 2: không biết SIM thì đổ chuông",
                cfg(rule(MOBILE_E164, RuleAction.BLOCK, SimScope.SLOT_2)), FakeFacts(), call(MOBILE, null), Decision.Ring
            ),
            Case(
                "quy tắc riêng SIM 2 thắng quy tắc mọi SIM cùng số",
                cfg(rule(MOBILE_E164, RuleAction.ALLOW), rule(MOBILE_E164, RuleAction.BLOCK, SimScope.SLOT_2)),
                FakeFacts(), call(MOBILE, 2), REJECT
            ),
            Case(
                "quy tắc mọi SIM áp dụng cho SIM còn lại",
                cfg(rule(MOBILE_E164, RuleAction.ALLOW), rule(MOBILE_E164, RuleAction.BLOCK, SimScope.SLOT_2)),
                FakeFacts(), call(MOBILE, 1), Decision.Ring
            ),
            Case(
                "wildcard riêng SIM 1 không hợp SIM 2 thì rơi về wildcard ngắn hơn",
                cfg(rule("+8424*", RuleAction.ALLOW, SimScope.SLOT_1), rule("+842*", RuleAction.BLOCK)),
                FakeFacts(), call(HANOI, 2), REJECT
            ),

            // --- tự trả lời
            Case(
                "AUTO_ANSWER số cụ thể, mặc định trả lời ngay (ẩn)",
                cfg(rule(MOBILE_E164, RuleAction.AUTO_ANSWER)), FakeFacts(), call(MOBILE), Decision.AutoAnswer(0)
            ),
            Case(
                "AUTO_ANSWER có độ trễ",
                cfg(rule(MOBILE_E164, RuleAction.AUTO_ANSWER), settings = autoAnswer5s),
                FakeFacts(), call(MOBILE), Decision.AutoAnswer(5)
            ),
            Case(
                "AUTO_ANSWER số cụ thể áp dụng kể cả khi có trong danh bạ",
                cfg(rule(MOBILE_E164, RuleAction.AUTO_ANSWER)),
                FakeFacts(contacts = setOf(MOBILE_E164)), call(MOBILE), Decision.AutoAnswer(0)
            ),
            Case(
                "AUTO_ANSWER bị tắt trong cài đặt",
                cfg(
                    rule(MOBILE_E164, RuleAction.AUTO_ANSWER),
                    settings = FilterSettings(autoAnswer = AutoAnswerSettings(enabled = false))
                ),
                FakeFacts(), call(MOBILE), Decision.Ring
            ),
            Case(
                "AUTO_ANSWER khi đang có cuộc khác",
                cfg(rule(MOBILE_E164, RuleAction.AUTO_ANSWER)),
                FakeFacts(otherLiveCall = true), call(MOBILE), Decision.Ring
            ),
            Case(
                "AUTO_ANSWER khi không có route riêng tư",
                cfg(rule(MOBILE_E164, RuleAction.AUTO_ANSWER)),
                FakeFacts(privateRoute = false), call(MOBILE), Decision.Ring
            ),
            Case(
                "AUTO_ANSWER wildcard, số lạ, không có lịch sử",
                cfg(rule("+842*", RuleAction.AUTO_ANSWER)), FakeFacts(), call(HANOI), Decision.AutoAnswer(0)
            ),
            Case(
                "AUTO_ANSWER wildcard, số trong danh bạ",
                cfg(rule("+842*", RuleAction.AUTO_ANSWER)),
                FakeFacts(contacts = setOf(HANOI_E164)), call(HANOI), Decision.Ring
            ),
            Case(
                "AUTO_ANSWER wildcard, số có trong lịch sử",
                cfg(rule("+842*", RuleAction.AUTO_ANSWER)),
                FakeFacts(history = setOf(HANOI_E164)), call(HANOI), Decision.Ring
            ),
            Case(
                "AUTO_ANSWER wildcard, cửa sổ lịch sử = 0 thì bỏ kiểm tra",
                cfg(
                    rule("+842*", RuleAction.AUTO_ANSWER),
                    settings = FilterSettings(autoAnswer = AutoAnswerSettings(historyWindowMin = 0))
                ),
                FakeFacts(history = setOf(HANOI_E164)), call(HANOI), Decision.AutoAnswer(0)
            ),
            Case(
                "AUTO_ANSWER số cụ thể không xét lịch sử",
                cfg(rule(HANOI_E164, RuleAction.AUTO_ANSWER)),
                FakeFacts(history = setOf(HANOI_E164)), call(HANOI), Decision.AutoAnswer(0)
            )
        )

        for (c in cases) {
            val result = DecisionEngine.evaluate(c.call, c.config, c.facts)
            assertEquals("${c.name} (lý do: ${result.reason})", c.expected, result.decision)
        }
    }

    @Test
    fun `ket qua mang theo id quy tac da ap dung`() {
        val r = rule(MOBILE_E164, RuleAction.BLOCK)
        val result = DecisionEngine.evaluate(call(MOBILE), cfg(r), FakeFacts())
        assertEquals(r.id, result.ruleId)
        assertNull(DecisionEngine.evaluate(call(MOBILE), cfg(), FakeFacts()).ruleId)
    }

    @Test
    fun `khong tra danh ba khi khong can`() {
        val noLookup = listOf(
            "không có quy tắc, không chặn số lạ" to Triple(cfg(), call(MOBILE), FakeFacts()),
            "số cụ thể BLOCK" to Triple(cfg(rule(MOBILE_E164, RuleAction.BLOCK)), call(MOBILE), FakeFacts()),
            "số cụ thể AUTO_ANSWER" to Triple(cfg(rule(MOBILE_E164, RuleAction.AUTO_ANSWER)), call(MOBILE), FakeFacts()),
            "wildcard ALLOW" to Triple(cfg(rule("+842*", RuleAction.ALLOW)), call(HANOI), FakeFacts()),
            "AUTO_ANSWER tắt" to Triple(
                cfg(rule("+842*", RuleAction.AUTO_ANSWER), settings = FilterSettings(autoAnswer = AutoAnswerSettings(enabled = false))),
                call(HANOI), FakeFacts()
            ),
            "AUTO_ANSWER có cuộc khác" to Triple(
                cfg(rule("+842*", RuleAction.AUTO_ANSWER)), call(HANOI), FakeFacts(otherLiveCall = true)
            ),
            "số ẩn" to Triple(cfg(settings = unknownBlocked), call(null), FakeFacts())
        )
        for ((name, t) in noLookup) {
            DecisionEngine.evaluate(t.second, t.first, t.third)
            assertEquals("$name: không được tra danh bạ", 0, t.third.contactLookups)
        }
    }

    @Test
    fun `tra danh ba dung mot lan khi can`() {
        val facts = FakeFacts()
        DecisionEngine.evaluate(call(HANOI), cfg(rule("+842*", RuleAction.AUTO_ANSWER)), facts)
        assertEquals(1, facts.contactLookups)
        assertEquals(1, facts.historyLookups)
        assertEquals(1440 * 60_000L, facts.lastHistoryWindowMs)
    }

    @Test
    fun `thieu quyen danh ba khong ap dung wildcard va chan so la nhung so cu the van chay`() {
        fun facts() = FakeFacts().also { it.contactsUnavailable = true }

        val wildcard = DecisionEngine.evaluate(call(HANOI), cfg(rule("+842*", RuleAction.BLOCK)), facts())
        assertEquals(Decision.Ring, wildcard.decision)
        assertTrue(wildcard.reason, wildcard.reason.contains("danh bạ"))

        val unknown = DecisionEngine.evaluate(call(MOBILE), cfg(settings = unknownBlocked), facts())
        assertEquals(Decision.Ring, unknown.decision)

        val exact = DecisionEngine.evaluate(call(MOBILE), cfg(rule(MOBILE_E164, RuleAction.BLOCK)), facts())
        assertEquals(REJECT, exact.decision)

        val hidden = DecisionEngine.evaluate(call(null), cfg(settings = FilterSettings(blockHidden = true)), facts())
        assertEquals(REJECT, hidden.decision)
    }

    @Test
    fun `an han khan cap huy moi quyet dinh chan nhung khong huy tu tra loi`() {
        val grace = { FakeFacts(emergencyGrace = true) }

        val blocked = listOf(
            "số cụ thể" to cfg(rule(MOBILE_E164, RuleAction.BLOCK)),
            "wildcard" to cfg(rule("+84*", RuleAction.BLOCK)),
            "số lạ" to cfg(settings = unknownBlocked)
        )
        for ((name, config) in blocked) {
            val r = DecisionEngine.evaluate(call(MOBILE), config, grace())
            assertEquals("$name: bị hủy trong ân hạn", Decision.Ring, r.decision)
            assertTrue(r.reason, r.reason.contains("khẩn cấp"))
        }

        val hidden = DecisionEngine.evaluate(call(null), cfg(settings = FilterSettings(blockHidden = true)), grace())
        assertEquals(Decision.Ring, hidden.decision)

        val auto = DecisionEngine.evaluate(call(MOBILE), cfg(rule(MOBILE_E164, RuleAction.AUTO_ANSWER)), grace())
        assertEquals(Decision.AutoAnswer(0), auto.decision)
    }

    @Test
    fun `khong co an han thi van chan`() {
        val r = DecisionEngine.evaluate(call(MOBILE), cfg(rule(MOBILE_E164, RuleAction.BLOCK)), FakeFacts(emergencyGrace = false))
        assertEquals(REJECT, r.decision)
    }

    @Test
    fun `loi khi hoi an han khong lam mat quyet dinh chan`() {
        val facts = FakeFacts().also { it.failOnEmergency = true }
        val r = DecisionEngine.evaluate(call(MOBILE), cfg(rule(MOBILE_E164, RuleAction.BLOCK)), facts)
        assertEquals(REJECT, r.decision)
    }

    @Test
    fun `loi bat ngo khi danh gia thi do chuong khong bao gio chan nham`() {
        val facts = FakeFacts().also { it.failOnOtherLiveCall = true }
        val r = DecisionEngine.evaluate(call(MOBILE), cfg(rule(MOBILE_E164, RuleAction.AUTO_ANSWER)), facts)
        assertEquals(Decision.Ring, r.decision)
        assertTrue(r.reason, r.reason.contains("lỗi"))
    }
}
