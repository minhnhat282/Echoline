package com.minhnhat.echoline.domain.model

import com.minhnhat.echoline.domain.engine.RuleIndex

/** Hành động của một quy tắc. */
enum class RuleAction { BLOCK, AUTO_ANSWER, ALLOW }

/**
 * Phạm vi SIM của quy tắc. Lưu theo khe (không theo subscriptionId, vì id đổi khi đổi SIM).
 * Khe SIM đánh số từ 1; khi không biết cuộc gọi đến từ SIM nào thì chỉ quy tắc [ANY] khớp.
 */
enum class SimScope {
    ANY, SLOT_1, SLOT_2;

    companion object {
        /** Phạm vi "cụ thể" tương ứng với một khe SIM, hoặc null nếu không biết/không hỗ trợ. */
        fun forSlot(simSlot: Int?): SimScope? = when (simSlot) {
            1 -> SLOT_1
            2 -> SLOT_2
            else -> null
        }
    }
}

/** Kiểu chặn: từ chối hẳn người gọi hoặc chỉ tắt chuông. */
enum class BlockStyle { REJECT, SILENCE }

/**
 * Một quy tắc.
 * @param pattern số cụ thể ở dạng E.164 ("+84981234567") hoặc wildcard là tiền tố E.164 kết thúc bằng '*' ("+842*").
 * @param display chuỗi người dùng đã nhập, chỉ để hiển thị.
 */
data class Rule(
    val id: String,
    val pattern: String,
    val display: String,
    val action: RuleAction,
    val sim: SimScope = SimScope.ANY,
    val note: String? = null,
    val createdAt: Long = 0L
) {
    val isWildcard: Boolean get() = pattern.endsWith("*")

    /** Tiền tố của wildcard (không có '*'). Với số cụ thể trả về chính [pattern]. */
    val prefix: String get() = pattern.removeSuffix("*")
}

data class AutoAnswerSettings(
    val enabled: Boolean = true,
    /** <= 0: trả lời ngay và ẩn hoàn toàn; > 0: đổ chuông bình thường rồi tự trả lời sau N giây. */
    val delaySec: Int = 0,
    val muteMic: Boolean = true,
    val hangUpAfterSec: Int = 60,
    /** Với wildcard: không tự trả lời nếu số có trong lịch sử cuộc gọi trong khoảng này. 0 = tắt kiểm tra. */
    val historyWindowMin: Int = 1440
)

data class FilterSettings(
    val blockHidden: Boolean = false,
    val blockUnknown: Boolean = false,
    val blockStyle: BlockStyle = BlockStyle.REJECT,
    /** Số ngày tạm không chặn sau khi người dùng gọi số khẩn cấp. */
    val emergencyGraceDays: Int = 2,
    val autoAnswer: AutoAnswerSettings = AutoAnswerSettings()
)

/** Quy tắc và cài đặt chung trong một đối tượng bất biến: một nguồn sự thật, không thể lệch phiên bản. */
data class FilterConfig(
    val rules: List<Rule> = emptyList(),
    val settings: FilterSettings = FilterSettings()
) {
    /** Chỉ mục tra nhanh, dựng một lần khi cần (không tham gia equals/hashCode). */
    val index: RuleIndex by lazy(LazyThreadSafetyMode.PUBLICATION) { RuleIndex(rules) }
}

/**
 * Thông tin một cuộc gọi đến.
 * @param number null hoặc rỗng nghĩa là số ẩn.
 * @param simSlot khe SIM (1-based), null nếu không biết.
 * @param region mã vùng ISO 3166 dùng để chuẩn hóa số nội địa ("VN").
 */
data class CallInfo(
    val number: String?,
    val simSlot: Int?,
    val region: String
)

sealed interface Decision {
    data object Ring : Decision
    data class Block(val style: BlockStyle) : Decision

    /** delaySec <= 0: trả lời ngay, ẩn hoàn toàn. > 0: đổ chuông rồi trả lời sau N giây. */
    data class AutoAnswer(val delaySec: Int) : Decision
}

/** Quyết định kèm lý do (tiếng Việt, ghi vào nhật ký) và id quy tắc đã áp dụng (nếu có). */
data class DecisionResult(
    val decision: Decision,
    val reason: String,
    val ruleId: String? = null
)
