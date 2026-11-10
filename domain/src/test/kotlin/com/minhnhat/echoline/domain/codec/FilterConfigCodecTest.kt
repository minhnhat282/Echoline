package com.minhnhat.echoline.domain.codec

import com.minhnhat.echoline.domain.codec.FilterConfigCodec.DecodeResult
import com.minhnhat.echoline.domain.model.AutoAnswerSettings
import com.minhnhat.echoline.domain.model.BlockStyle
import com.minhnhat.echoline.domain.model.FilterConfig
import com.minhnhat.echoline.domain.model.FilterSettings
import com.minhnhat.echoline.domain.model.Rule
import com.minhnhat.echoline.domain.model.RuleAction
import com.minhnhat.echoline.domain.model.SimScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterConfigCodecTest {

    private fun ok(json: String, region: String = "VN") =
        FilterConfigCodec.decode(json, region) as DecodeResult.Ok

    @Test
    fun `ghi roi doc lai giu nguyen quy tac va cai dat`() {
        val config = FilterConfig(
            rules = listOf(
                Rule("1", "+842*", "02*", RuleAction.AUTO_ANSWER, SimScope.SLOT_2, "tổng đài", 10L),
                Rule("2", "+84981234567", "0981234567", RuleAction.BLOCK, SimScope.ANY, null, 20L),
                Rule("3", "+84981234567", "0981234567", RuleAction.ALLOW, SimScope.SLOT_1, null, 30L)
            ),
            settings = FilterSettings(
                blockHidden = true,
                blockUnknown = true,
                blockStyle = BlockStyle.SILENCE,
                emergencyGraceDays = 5,
                autoAnswer = AutoAnswerSettings(
                    enabled = false, delaySec = 7, muteMic = false, hangUpAfterSec = 90, historyWindowMin = 60
                )
            )
        )
        val decoded = ok(FilterConfigCodec.encode(config))
        assertEquals(FilterConfigCodec.SCHEMA_VERSION, decoded.schemaVersion)
        assertEquals(0, decoded.skipped)
        assertEquals(config, decoded.config)
        assertNull(decoded.config.rules[1].note)
    }

    @Test
    fun `cau hinh mac dinh ghi roi doc lai`() {
        assertEquals(FilterConfig(), ok(FilterConfigCodec.encode(FilterConfig())).config)
    }

    @Test
    fun `chuoi rong la cau hinh mac dinh khong phai loi`() {
        for (blank in listOf("", "   ", "\n")) {
            val decoded = ok(blank)
            assertTrue(decoded.config.rules.isEmpty())
            assertEquals(FilterSettings(), decoded.config.settings)
        }
    }

    @Test
    fun `du lieu hong la loi chu khong phai danh sach rong`() {
        val bad = listOf("{khong phai json", "[1,2,3]", """{"foo":1}""", """{"rules":"x"}""", "null", "12")
        for (json in bad) {
            assertTrue("'$json' phải là Failed", FilterConfigCodec.decode(json, "VN") is DecodeResult.Failed)
        }
    }

    @Test
    fun `ten truong bi doi khi minify thi bao loi chu khong am tham mat quy tac`() {
        val obfuscated = """{"a":2,"rules":[{"a":"x","b":"+842*","c":"02*","d":"BLOCK"}]}"""
        assertTrue(FilterConfigCodec.decode(obfuscated, "VN") is DecodeResult.Failed)
    }

    @Test
    fun `danh sach quy tac rong hop le la rong`() {
        val decoded = ok("""{"schemaVersion":3,"rules":[]}""")
        assertTrue(decoded.config.rules.isEmpty())
        assertEquals(0, decoded.skipped)
    }

    @Test
    fun `muc sai bi bo qua va duoc dem`() {
        val json = """{"schemaVersion":3,"rules":[
            {"id":"a","pattern":"+842*","action":"BLOCK"},
            {"id":"b","pattern":"+84981234567","action":"KHONG_CO"},
            {"id":"c","pattern":"+84981234567","action":"BLOCK","sim":"SLOT_9"},
            {"id":"d","pattern":"+0*","action":"BLOCK"},
            {"id":"e","action":"BLOCK"},
            "rac"
        ]}"""
        val decoded = ok(json)
        assertEquals(listOf("a"), decoded.config.rules.map { it.id })
        assertEquals(5, decoded.skipped)
    }

    @Test
    fun `du lieu schema 1 duoc nang wildcard len E164 va co mac dinh cho truong moi`() {
        val legacy = """{"schemaVersion":1,"rules":[
            {"id":"a","pattern":"02*","display":"02*","action":"AUTO_ANSWER","createdAt":5},
            {"id":"b","pattern":"+842473001234","display":"024 7300 1234","action":"BLOCK","createdAt":6}
        ]}"""
        val decoded = ok(legacy)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(FilterSettings(), decoded.config.settings)
        val rules = decoded.config.rules
        assertEquals("+842*", rules[0].pattern)
        assertEquals("02*", rules[0].display)
        assertEquals(SimScope.ANY, rules[0].sim)
        assertEquals("+842473001234", rules[1].pattern)
        assertEquals(RuleAction.BLOCK, rules[1].action)
    }

    @Test
    fun `thieu schemaVersion duoc coi la schema 1`() {
        assertEquals(1, ok("""{"rules":[{"pattern":"+84981234567","action":"block"}]}""").schemaVersion)
    }

    @Test
    fun `ten hanh dong khong phan biet hoa thuong va id thieu duoc cap moi`() {
        val decoded = ok("""{"rules":[{"pattern":"+84981234567","action":" auto_answer "}]}""")
        val rule = decoded.config.rules.single()
        assertEquals(RuleAction.AUTO_ANSWER, rule.action)
        assertTrue(rule.id.isNotBlank())
        assertEquals("+84981234567", rule.display)
    }

    @Test
    fun `trung pattern va SIM thi giu muc sau`() {
        val json = """{"rules":[
            {"id":"a","pattern":"+84981234567","action":"BLOCK"},
            {"id":"b","pattern":"+84981234567","action":"ALLOW"},
            {"id":"c","pattern":"+84981234567","action":"BLOCK","sim":"SLOT_1"}
        ]}"""
        val rules = ok(json).config.rules
        assertEquals(listOf("b", "c"), rules.map { it.id })
    }

    @Test
    fun `cai dat thieu hoac sai kieu thi dung mac dinh va so duoc ke vao khoang hop ly`() {
        val json = """{"schemaVersion":3,"rules":[],"settings":{
            "blockHidden":"co","blockUnknown":true,"blockStyle":"khong-biet",
            "emergencyGraceDays":999,
            "autoAnswer":{"delaySec":-5,"hangUpAfterSec":0,"historyWindowMin":99999999,"enabled":false}
        }}"""
        val s = ok(json).config.settings
        assertEquals(false, s.blockHidden)
        assertEquals(true, s.blockUnknown)
        assertEquals(BlockStyle.REJECT, s.blockStyle)
        assertEquals(30, s.emergencyGraceDays)
        assertEquals(0, s.autoAnswer.delaySec)
        assertEquals(1, s.autoAnswer.hangUpAfterSec)
        assertEquals(43_200, s.autoAnswer.historyWindowMin)
        assertEquals(false, s.autoAnswer.enabled)
        assertEquals(true, s.autoAnswer.muteMic)
    }
}
