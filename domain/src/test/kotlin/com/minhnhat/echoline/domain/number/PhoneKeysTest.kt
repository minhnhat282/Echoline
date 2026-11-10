package com.minhnhat.echoline.domain.number

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneKeysTest {

    @Test
    fun `moi dang cua cung mot so ban VN cho cung khoa`() {
        val forms = listOf(
            "+84 24 7300 1234",
            "+842473001234",
            "842473001234",
            "024 7300 1234",
            "02473001234",
            "  (024) 7300-1234  "
        )
        for (form in forms) {
            val keys = PhoneKeys.keysOf(form, "VN")
            assertEquals("exactKey của '$form'", "+842473001234", keys.exactKey)
            assertEquals("national của '$form'", "02473001234", keys.national)
        }
    }

    @Test
    fun `so di dong VN`() {
        val keys = PhoneKeys.keysOf("0981234567", "VN")
        assertEquals("+84981234567", keys.exactKey)
        assertEquals("0981234567", keys.national)
    }

    @Test
    fun `so nuoc ngoai khong co khoa noi dia`() {
        val us = PhoneKeys.keysOf("+1 202 555 0123", "VN")
        assertEquals("+12025550123", us.exactKey)
        assertNull(us.national)

        val egypt = PhoneKeys.keysOf("+20 100 123 4567", "VN")
        assertNull(egypt.national)
    }

    @Test
    fun `chuoi khong phai so thi khong co khoa chuan hoa`() {
        val keys = PhoneKeys.keysOf("abc", "VN")
        assertEquals("", keys.digits)
        assertEquals("", keys.exactKey)
        assertNull(keys.national)
    }

    @Test
    fun `region rong dung mac dinh VN`() {
        assertEquals("+84981234567", PhoneKeys.keysOf("0981234567", "").exactKey)
    }

    @Test
    fun `doi dau so noi dia sang E164`() {
        val table = listOf(
            Triple("02", "VN", "+842"),
            Triple("1900", "VN", "+841900"),
            Triple("0243800", "VN", "+84243800"),
            Triple("212", "US", "+1212")
        )
        for ((digits, region, expected) in table) {
            assertEquals("$digits/$region", expected, PhoneKeys.nationalPrefixToE164(digits, region))
        }
        assertNull(PhoneKeys.nationalPrefixToE164("0", "VN"))
        assertNull(PhoneKeys.nationalPrefixToE164("02", "ZZ"))
    }
}
