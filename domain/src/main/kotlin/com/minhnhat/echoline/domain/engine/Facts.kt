package com.minhnhat.echoline.domain.engine

import com.minhnhat.echoline.domain.number.NumberKeys

/** Không thể biết số có trong danh bạ hay không (ví dụ chưa cấp quyền Danh bạ). */
class ContactsUnavailableException(message: String) : IllegalStateException(message)

/**
 * Các dữ kiện môi trường mà engine có thể hỏi. Engine chỉ hỏi khi thật sự cần (tính lười), và một dữ kiện tốn kém
 * như danh bạ chỉ được hỏi khi quyết định sắp áp dụng khác "đổ chuông".
 *
 * Mọi hàm phải trả lời nhanh và không chặn (đường nhanh chạy trên luồng chính). Bản triển khai thật ở :app
 * đọc từ bộ nhớ đệm; nếu chưa sẵn sàng thì ném [ContactsUnavailableException] (danh bạ) hoặc ngoại lệ khác,
 * engine sẽ rơi về "đổ chuông".
 */
interface Facts {
    /** @throws ContactsUnavailableException nếu không biết được. */
    fun isContact(keys: NumberKeys): Boolean

    fun hasOtherLiveCall(): Boolean

    /** Có đường âm thanh riêng tư (loa trong hoặc tai nghe có dây) để tự trả lời an toàn. */
    fun hasPrivateRoute(): Boolean

    fun hasRecentHistory(keys: NumberKeys, windowMs: Long): Boolean

    /** true nếu người dùng vừa gọi số khẩn cấp (trong khoảng ân hạn): tạm không chặn. */
    fun emergencyGraceActive(): Boolean
}
