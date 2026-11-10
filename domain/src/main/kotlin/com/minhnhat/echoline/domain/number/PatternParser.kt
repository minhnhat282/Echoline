package com.minhnhat.echoline.domain.number

sealed class PatternParseResult {
    data class Valid(val pattern: String, val display: String, val isWildcard: Boolean) : PatternParseResult()
    data object Invalid : PatternParseResult()
    data object TooShort : PatternParseResult()

    /**
     * Wildcard viết theo dạng quốc tế nhưng bỏ dấu '+' (ví dụ "8424*"): mơ hồ giữa "đầu số 8424 trong nước"
     * và "mã quốc gia 84 + 24", nên từ chối. Người dùng phải viết "024*" hoặc "+84 24*".
     */
    data object InternationalWildcard : PatternParseResult()
}

/** Kiểm tra và chuẩn hóa chuỗi mẫu người dùng nhập (số cụ thể hoặc wildcard). */
object PatternParser {
    private const val MIN_WILDCARD_DIGITS = 2
    private const val MIN_EXACT_DIGITS = 3
    private const val ALLOWED_PUNCTUATION = " -.()"

    /** Wildcard có ít hơn số chữ số này (không tính mã quốc gia và số 0 đầu) bị coi là "rộng". */
    const val BROAD_WILDCARD_SIGNIFICANT_DIGITS = 3

    /**
     * - Số cụ thể: nhận mọi dạng (có/không +84, khoảng trắng, gạch...). Kết quả là E.164.
     * - Wildcard: chữ số và đúng một dấu '*' ở cuối. Kết quả là TIỀN TỐ E.164, không phụ thuộc region lúc so khớp:
     *     "02*" -> "+842*", "1900*" -> "+841900*", "+84 24*" -> "+8424*".
     *   Dạng "8424*" (thiếu '+', bắt đầu bằng mã quốc gia) bị từ chối vì mơ hồ.
     */
    fun parse(input: String, region: String): PatternParseResult {
        val text = input.trim()
        if (text.isEmpty()) return PatternParseResult.Invalid

        val starCount = text.count { it == '*' }
        if (starCount > 1) return PatternParseResult.Invalid

        if (starCount == 1) return parseWildcard(text, region)

        val body = if (text.startsWith("+")) text.drop(1) else text
        if (body.any { !(it.isDigit() || ALLOWED_PUNCTUATION.indexOf(it) >= 0) }) {
            return PatternParseResult.Invalid
        }
        val digits = body.filter { it.isDigit() }
        if (digits.isEmpty()) return PatternParseResult.Invalid
        if (digits.length < MIN_EXACT_DIGITS) return PatternParseResult.TooShort

        return PatternParseResult.Valid(
            pattern = PhoneKeys.keysOf(text, region).exactKey,
            display = text,
            isWildcard = false
        )
    }

    private fun parseWildcard(text: String, region: String): PatternParseResult {
        if (!text.endsWith("*")) return PatternParseResult.Invalid
        val body = text.dropLast(1).trim()
        val explicitPlus = body.startsWith("+")
        val core = if (explicitPlus) body.drop(1) else body
        if (core.any { !(it.isDigit() || ALLOWED_PUNCTUATION.indexOf(it) >= 0) }) {
            return PatternParseResult.Invalid
        }
        val digits = core.filter { it.isDigit() }
        if (digits.isEmpty()) return PatternParseResult.Invalid
        if (digits.length < MIN_WILDCARD_DIGITS) return PatternParseResult.TooShort

        val prefix: String = if (explicitPlus) {
            val ccLen = PhoneKeys.countryCodeLength(digits) ?: return PatternParseResult.Invalid
            if (digits.length <= ccLen) return PatternParseResult.TooShort
            "+$digits"
        } else {
            if (looksInternational(digits, region)) return PatternParseResult.InternationalWildcard
            PhoneKeys.nationalPrefixToE164(digits, region) ?: return PatternParseResult.TooShort
        }
        return PatternParseResult.Valid(pattern = "$prefix*", display = text, isWildcard = true)
    }

    /**
     * Mẫu không có '+' mà bắt đầu bằng mã quốc gia ("8424*") thì mơ hồ. Riêng nước có tiền tố nội địa trùng
     * mã quốc gia (Mỹ/Canada: mã 1, tiền tố 1) thì không loại.
     */
    private fun looksInternational(digits: String, region: String): Boolean {
        val countryCode = PhoneKeys.regionCountryCode(region)
        if (countryCode <= 0) return false
        if (!digits.startsWith(countryCode.toString())) return false
        val ndd = PhoneKeys.nddPrefix(region)
        return !(ndd.isNotEmpty() && digits.startsWith(ndd))
    }

    /**
     * Wildcard có tiền tố ngắn ("+842*" = "02*") khớp rất nhiều số. Đếm chữ số có nghĩa: không tính mã quốc gia
     * (số 0 đầu đã bị bỏ khi chuẩn hóa).
     */
    fun isBroadWildcard(pattern: String): Boolean {
        if (!pattern.endsWith("*")) return false
        val body = pattern.removeSuffix("*")
        val digits = body.filter { it.isDigit() }
        val significant = if (body.startsWith("+")) {
            val ccLen = PhoneKeys.countryCodeLength(digits) ?: 0
            digits.length - ccLen
        } else {
            // dạng chưa chuẩn hóa: đếm chữ số nội địa gồm cả số 0 đầu
            digits.length - 1
        }
        return significant < BROAD_WILDCARD_SIGNIFICANT_DIGITS
    }
}
