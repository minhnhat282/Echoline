package com.minhnhat.echoline.domain.number

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Các "khóa so khớp" sinh ra từ một số điện thoại.
 *
 * @param exactKey Dạng E.164 ("+842473001234") nếu parse được, ngược lại là toàn bộ chữ số.
 *                 Dùng để so khớp cả quy tắc số cụ thể lẫn wildcard (wildcard là tiền tố E.164).
 * @param national Chữ số ở dạng nội địa ("02473001234"); chỉ có khi số cùng quốc gia với region.
 * @param digits   Toàn bộ chữ số của chuỗi gốc.
 */
data class NumberKeys(
    val exactKey: String,
    val national: String?,
    val digits: String
)

object PhoneKeys {
    const val DEFAULT_REGION = "VN"

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /**
     * Với region "VN", cả "+84 24 7300 1234", "842473001234", "024 7300 1234", "02473001234"
     * đều cho exactKey = "+842473001234" và national = "02473001234".
     */
    fun keysOf(raw: String, region: String): NumberKeys {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        val hasPlus = trimmed.startsWith("+")
        var exact = if (hasPlus) "+$digits" else digits
        var national: String? = null
        val safeRegion = region.ifBlank { DEFAULT_REGION }

        try {
            val regionCode = util.getCountryCodeForRegion(safeRegion)
            var parsed = util.parse(trimmed, safeRegion)
            // Một số nhà mạng gửi số quốc tế mà thiếu dấu '+' (ví dụ "842473001234"):
            // nếu thêm '+' vào mà thành một số hợp lệ thì coi đó là số quốc tế.
            if (!hasPlus && regionCode > 0) {
                val cc = regionCode.toString()
                if (digits.startsWith(cc) && digits.length >= cc.length + 7) {
                    // Thử riêng: nếu lần parse này lỗi thì vẫn giữ kết quả parse đầu, không rơi về chuỗi số thô.
                    try {
                        val withPlus = util.parse("+$digits", safeRegion)
                        if (util.isValidNumber(withPlus)) parsed = withPlus
                    } catch (ignored: NumberParseException) {
                        // giữ [parsed] của lần đầu
                    }
                }
            }
            if (util.isPossibleNumber(parsed)) {
                exact = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
                if (regionCode > 0 && parsed.countryCode == regionCode) {
                    val nat = util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
                        .filter { it.isDigit() }
                    if (nat.isNotEmpty()) national = nat
                }
            }
        } catch (ignored: NumberParseException) {
            // giữ giá trị dự phòng ở trên
        }

        return NumberKeys(exactKey = exact, national = national, digits = digits)
    }

    /**
     * Đổi một đầu số nội địa (chỉ chữ số: "02", "0243800", "1900") thành tiền tố E.164 theo [region].
     * Số 0 đầu (tiền tố trung kế của region) được bỏ. Trả null nếu region không xác định hoặc sau khi bỏ
     * không còn chữ số nào.
     */
    fun nationalPrefixToE164(digits: String, region: String): String? {
        val safeRegion = region.ifBlank { DEFAULT_REGION }
        val countryCode = util.getCountryCodeForRegion(safeRegion)
        if (countryCode <= 0) return null
        val ndd = nddPrefix(safeRegion)
        val rest = if (ndd.isNotEmpty() && digits.startsWith(ndd)) digits.removePrefix(ndd) else digits
        if (rest.isEmpty()) return null
        return "+$countryCode$rest"
    }

    /** Độ dài mã quốc gia ở đầu chuỗi chữ số (0 đầu không bao giờ là mã quốc gia), hoặc null nếu không có. */
    internal fun countryCodeLength(digits: String): Int? {
        if (digits.isEmpty() || digits[0] == '0') return null
        val supported = util.supportedCallingCodes
        for (len in 1..3) {
            if (digits.length < len) return null
            val code = digits.substring(0, len).toIntOrNull() ?: return null
            if (code in supported) return len
        }
        return null
    }

    internal fun regionCountryCode(region: String): Int =
        util.getCountryCodeForRegion(region.ifBlank { DEFAULT_REGION })

    internal fun nddPrefix(region: String): String =
        util.getNddPrefixForRegion(region.ifBlank { DEFAULT_REGION }, true).orEmpty()

    /**
     * Nạp sẵn dữ liệu của libphonenumber (lần dùng đầu khá chậm). Gọi một lần ở luồng nền lúc khởi động
     * tiến trình để cuộc gọi đầu tiên không phải trả giá này.
     */
    fun warmUp(region: String) {
        keysOf("+84 24 7300 1234", region)
        keysOf("0243800 1234", region)
        keysOf("+1 202 555 0123", region)
    }
}
