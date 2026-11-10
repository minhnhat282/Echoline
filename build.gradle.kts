// AGP 9 có sẵn Kotlin (built-in Kotlin): KHÔNG áp dụng plugin org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
