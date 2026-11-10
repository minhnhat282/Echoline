package com.minhnhat.echoline.domain.engine

import com.minhnhat.echoline.domain.model.Rule
import com.minhnhat.echoline.domain.model.RuleAction
import com.minhnhat.echoline.domain.model.SimScope
import com.minhnhat.echoline.domain.number.PhoneKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleIndexTest {

    private fun rule(id: String, pattern: String, sim: SimScope = SimScope.ANY) =
        Rule(id = id, pattern = pattern, display = pattern, action = RuleAction.BLOCK, sim = sim)

    private fun keys(raw: String) = PhoneKeys.keysOf(raw, "VN")

    @Test
    fun `so cu the khop dung va khong khop so khac`() {
        val index = RuleIndex(listOf(rule("a", "+84981234567")))
        assertEquals("a", index.findExact(keys("0981234567"), 1)?.id)
        assertEquals("a", index.findExact(keys("+84 98 123 4567"), null)?.id)
        assertNull(index.findExact(keys("0981234568"), 1))
    }

    @Test
    fun `wildcard tien to dai nhat thang`() {
        val index = RuleIndex(listOf(rule("ngan", "+842*"), rule("dai", "+84243800*"), rule("giua", "+8424*")))
        assertEquals("dai", index.findWildcard(keys("0243800 1234"), 1)?.id)
        assertEquals("giua", index.findWildcard(keys("0247300 1234"), 1)?.id)
        assertEquals("ngan", index.findWildcard(keys("0283800 1234"), 1)?.id)
        assertNull(index.findWildcard(keys("0981234567"), 1))
    }

    @Test
    fun `so khong parse duoc khong khop wildcard`() {
        val index = RuleIndex(listOf(rule("a", "+842*")))
        assertNull(index.findWildcard(keys("abc"), 1))
    }

    @Test
    fun `quy tac dung SIM thang quy tac moi SIM`() {
        val index = RuleIndex(
            listOf(rule("moi", "+84981234567"), rule("sim2", "+84981234567", SimScope.SLOT_2))
        )
        assertEquals("sim2", index.findExact(keys("0981234567"), 2)?.id)
        assertEquals("moi", index.findExact(keys("0981234567"), 1)?.id)
        assertEquals("moi", index.findExact(keys("0981234567"), null)?.id)
    }

    @Test
    fun `quy tac rieng SIM khong khop khi SIM khac hoac khong biet`() {
        val index = RuleIndex(listOf(rule("sim2", "+84981234567", SimScope.SLOT_2)))
        assertEquals("sim2", index.findExact(keys("0981234567"), 2)?.id)
        assertNull(index.findExact(keys("0981234567"), 1))
        assertNull(index.findExact(keys("0981234567"), null))
    }

    @Test
    fun `wildcard dai nhat khong hop SIM thi thu tien to ngan hon`() {
        val index = RuleIndex(listOf(rule("ngan", "+842*"), rule("dai-sim2", "+8424*", SimScope.SLOT_2)))
        assertEquals("dai-sim2", index.findWildcard(keys("0247300 1234"), 2)?.id)
        assertEquals("ngan", index.findWildcard(keys("0247300 1234"), 1)?.id)
    }

    @Test
    fun `wildcard khong chuan hoa bi bo qua thay vi khop nham`() {
        val index = RuleIndex(listOf(rule("cu", "02*"), rule("ngan", "+8*")))
        assertNull(index.findWildcard(keys("0247300 1234"), 1))
    }
}
