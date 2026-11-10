package com.minhnhat.echoline.data

import android.content.Context

/** getSystemService(Class) có thể trả về null; hàm này gom việc kiểm tra về một chỗ. */
inline fun <reified T : Any> Context.systemService(): T =
    getSystemService(T::class.java) ?: throw IllegalStateException("Thiếu dịch vụ hệ thống ${T::class.java.simpleName}")
