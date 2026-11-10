package com.minhnhat.echoline.domain.codec

import com.minhnhat.echoline.domain.model.Rule
import com.minhnhat.echoline.domain.model.RuleAction
import com.minhnhat.echoline.domain.model.SimScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTransferTest {

    private fun ok(json: String) = RulesTransfer.parse(json, "VN", now = 100L) as TransferParseResult.Ok

    @Test
    fun `xuat roi nhap lai giu pattern hanh dong SIM ghi chu va thoi gian`() {
        val rules = listOf(
            Rule("x", "+842*", "02*", RuleAction.AUTO_ANSWER, SimScope.SLOT_2, "tổng đài", 10L),
            Rule("y", "+84981234567", "0981234567", RuleAction.BLOCK, SimScope.ANY, null, 20L)
        )
        val result = ok(RulesTransfer.export(rules, now = 999L))
        assertEquals(0, result.skipped)
        assertEquals(
            rules.map { listOf(it.pattern, it.display, it.action, it.sim, it.note, it.createdAt) },
            result.rules.map { listOf(it.pattern, it.display, it.action, it.sim, it.note, it.createdAt) }
        )
    }

    @Test
    fun `doc duoc tep xuat cua ban dau`() {
        val old = """{
            "type":"mndialer-call-rules","schemaVersion":1,"exportedAt":1,
            "rules":[
              {"pattern":"02*","display":"02*","action":"auto_answer","note":"cũ","createdAt":5},
              {"pattern":"0981234567","display":"0981234567","action":"BLOCK","createdAt":6},
              {"display":"1900*","action":"BLOCK"}
            ]}"""
        val result = ok(old)
        assertEquals(0, result.skipped)
        val rules = result.rules
        assertEquals("+842*", rules[0].pattern)
        assertEquals(RuleAction.AUTO_ANSWER, rules[0].action)
        assertEquals("cũ", rules[0].note)
        assertEquals("+84981234567", rules[1].pattern)
        assertEquals("+841900*", rules[2].pattern)
        assertEquals(100L, rules[2].createdAt)
        assertTrue(rules.all { it.sim == SimScope.ANY })
    }

    @Test
    fun `nhan ca mang o goc`() {
        val result = ok("""[{"pattern":"0981234567","action":"BLOCK"}]""")
        assertEquals("+84981234567", result.rules.single().pattern)
    }

    @Test
    fun `muc sai bi bo qua va duoc dem`() {
        val json = """{"rules":[
            {"pattern":"0981234567","action":"BLOCK"},
            {"pattern":"8424*","action":"BLOCK"},
            {"pattern":"12","action":"BLOCK"},
            {"pattern":"0981234568","action":"NGHE_LA"},
            {"pattern":"0981234569","action":"BLOCK","sim":"SIM_9"},
            "rac"
        ]}"""
        val result = ok(json)
        assertEquals(1, result.rules.size)
        assertEquals(5, result.skipped)
    }

    @Test
    fun `trung pattern va SIM thi giu mot`() {
        val json = """{"rules":[
            {"pattern":"0981234567","action":"BLOCK"},
            {"pattern":"+84 98 123 4567","action":"ALLOW"},
            {"pattern":"0981234567","action":"BLOCK","sim":"SLOT_1"}
        ]}"""
        val rules = ok(json).rules
        assertEquals(2, rules.size)
        assertEquals(RuleAction.ALLOW, rules[0].action)
    }

    @Test
    fun `tep khong phai tep quy tac`() {
        for (json in listOf("", "khong phai json", """{"foo":1}""", "12", "null", """{"rules":3}""")) {
            assertEquals("'$json'", TransferParseResult.Invalid, RulesTransfer.parse(json, "VN", 0L))
        }
    }

    @Test
    fun `qua nhieu muc hoac qua lon bi tu choi`() {
        val tooMany = buildString {
            append("[")
            repeat(RulesTransfer.MAX_RULES + 1) { i ->
                if (i > 0) append(",")
                append("""{"pattern":"09812${i.toString().padStart(5, '0')}","action":"BLOCK"}""")
            }
            append("]")
        }
        assertEquals(TransferParseResult.Invalid, RulesTransfer.parse(tooMany, "VN", 0L))

        val huge = "[" + " ".repeat(RulesTransfer.MAX_CHARS) + "]"
        assertEquals(TransferParseResult.Invalid, RulesTransfer.parse(huge, "VN", 0L))
    }

    @Test
    fun `tep xuat sap xep theo thoi gian tao`() {
        val rules = listOf(
            Rule("b", "+84981234568", "b", RuleAction.BLOCK, createdAt = 2L),
            Rule("a", "+84981234567", "a", RuleAction.BLOCK, createdAt = 1L)
        )
        val patterns = ok(RulesTransfer.export(rules, 0L)).rules.map { it.pattern }
        assertEquals(listOf("+84981234567", "+84981234568"), patterns)
    }
}
