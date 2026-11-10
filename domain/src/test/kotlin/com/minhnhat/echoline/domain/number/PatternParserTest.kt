package com.minhnhat.echoline.domain.number

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternParserTest {

    private fun valid(input: String, region: String = "VN") =
        PatternParser.parse(input, region) as PatternParseResult.Valid

    @Test
    fun `mau hop le`() {
        val inputs = listOf("02*", "0243800*", "024 7300 1234", "+84 24 7300 1234", "024*", "0843*", "1900*")
        for (input in inputs) {
            assertTrue("'$input' phải hợp lệ", PatternParser.parse(input, "VN") is PatternParseResult.Valid)
        }
        assertTrue(PatternParser.parse("1212*", "US") is PatternParseResult.Valid)
    }

    @Test
    fun `mau khong hop le`() {
        val invalid = listOf("", "*", "*02", "0*2", "02**", "+0123*", "abc", "02a*", "+84 24 abc")
        for (input in invalid) {
            assertEquals("'$input'", PatternParseResult.Invalid, PatternParser.parse(input, "VN"))
        }
    }

    @Test
    fun `mau qua ngan`() {
        val tooShort = listOf("+84*", "0*", "1*", "12")
        for (input in tooShort) {
            assertEquals("'$input'", PatternParseResult.TooShort, PatternParser.parse(input, "VN"))
        }
    }

    @Test
    fun `wildcard dang quoc te thieu dau cong bi tu choi`() {
        for (input in listOf("8424*", "84 24*", "84*")) {
            assertEquals("'$input'", PatternParseResult.InternationalWildcard, PatternParser.parse(input, "VN"))
        }
    }

    @Test
    fun `wildcard duoc chuan hoa thanh tien to E164`() {
        val table = listOf(
            Triple("02*", "VN", "+842*"),
            Triple("0243800*", "VN", "+84243800*"),
            Triple("024 3800*", "VN", "+84243800*"),
            Triple("1900*", "VN", "+841900*"),
            Triple("+84 24*", "VN", "+8424*"),
            Triple("024*", "VN", "+8424*"),
            Triple("1212*", "US", "+1212*"),
            Triple("+1 212*", "VN", "+1212*"),
            Triple("+20 100*", "VN", "+20100*")
        )
        for ((input, region, expected) in table) {
            val result = valid(input, region)
            assertEquals("$input/$region", expected, result.pattern)
            assertTrue(result.isWildcard)
            assertEquals(input, result.display)
        }
    }

    @Test
    fun `so cu the duoc chuan hoa thanh E164`() {
        val table = listOf(
            "024 7300 1234" to "+842473001234",
            "+84 24 7300 1234" to "+842473001234",
            "0981234567" to "+84981234567"
        )
        for ((input, expected) in table) {
            val result = valid(input)
            assertEquals(input, expected, result.pattern)
            assertFalse(result.isWildcard)
        }
    }

    @Test
    fun `wildcard rong`() {
        val broad = listOf("+842*", "+8424*", "02*", "024*")
        for (p in broad) assertTrue("'$p' phải là rộng", PatternParser.isBroadWildcard(p))

        val notBroad = listOf("+84243*", "+842473001234", "0243*")
        for (p in notBroad) assertFalse("'$p' không rộng", PatternParser.isBroadWildcard(p))
    }
}
