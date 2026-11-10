package com.minhnhat.echoline.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/*
 * Bảng màu: nâu (màu nhấn) trên nền kem ấm, kèm màu phụ nâu trầm và màu bổ trợ xanh ô-liu nhạt.
 * Cố ý KHÔNG dùng "màu động" theo hình nền (Android 12+) để giao diện luôn giữ màu nâu của ứng dụng.
 * Màu ngữ nghĩa của cuộc gọi (nút Trả lời xanh lá, Từ chối/Cúp đỏ) nằm riêng trong ui/incall, không đổi theo bảng này.
 */
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF7A4A2B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC6),
    onPrimaryContainer = Color(0xFF2B1507),
    inversePrimary = Color(0xFFFFB68C),
    secondary = Color(0xFF765848),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFBDCC9),
    onSecondaryContainer = Color(0xFF2B170B),
    tertiary = Color(0xFF5F6134),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE4E6AD),
    onTertiaryContainer = Color(0xFF1C1D00),
    background = Color(0xFFFFF8F5),
    onBackground = Color(0xFF201A17),
    surface = Color(0xFFFFF8F5),
    onSurface = Color(0xFF201A17),
    surfaceVariant = Color(0xFFF4DED3),
    onSurfaceVariant = Color(0xFF52443C),
    surfaceTint = Color(0xFF7A4A2B),
    inverseSurface = Color(0xFF362F2B),
    inverseOnSurface = Color(0xFFFBEEE8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF85736B),
    outlineVariant = Color(0xFFD7C2B8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFF8F5),
    surfaceDim = Color(0xFFE4D8D1),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EA),
    surfaceContainer = Color(0xFFFBEAE2),
    surfaceContainerHigh = Color(0xFFF5E4DC),
    surfaceContainerHighest = Color(0xFFEFDED6)
)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFB68C),
    onPrimary = Color(0xFF4A2810),
    primaryContainer = Color(0xFF63391D),
    onPrimaryContainer = Color(0xFFFFDCC6),
    inversePrimary = Color(0xFF7A4A2B),
    secondary = Color(0xFFE5BFA9),
    onSecondary = Color(0xFF43291B),
    secondaryContainer = Color(0xFF5C3F30),
    onSecondaryContainer = Color(0xFFFFDCC8),
    tertiary = Color(0xFFC8CA93),
    onTertiary = Color(0xFF303209),
    tertiaryContainer = Color(0xFF47491E),
    onTertiaryContainer = Color(0xFFE4E6AD),
    background = Color(0xFF181210),
    onBackground = Color(0xFFEDE0DA),
    surface = Color(0xFF181210),
    onSurface = Color(0xFFEDE0DA),
    surfaceVariant = Color(0xFF52443C),
    onSurfaceVariant = Color(0xFFD7C2B8),
    surfaceTint = Color(0xFFFFB68C),
    inverseSurface = Color(0xFFEDE0DA),
    inverseOnSurface = Color(0xFF362F2B),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF9F8D84),
    outlineVariant = Color(0xFF52443C),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3F3733),
    surfaceDim = Color(0xFF181210),
    surfaceContainerLowest = Color(0xFF130D0A),
    surfaceContainerLow = Color(0xFF201A17),
    surfaceContainer = Color(0xFF251E1B),
    surfaceContainerHigh = Color(0xFF302824),
    surfaceContainerHighest = Color(0xFF3B332F)
)

@Composable
fun EcholineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors
    ) {
        // Surface cung cấp nền và MÀU CHỮ MẶC ĐỊNH (onBackground) cho mọi màn hình bên trong. Thiếu nó, chữ không đặt màu riêng
        // (ví dụ ở các màn hình không nằm trong Scaffold như Nhật ký quyết định, Nhật ký lỗi) sẽ có màu đen mặc định và
        // không đọc được trên nền tối.
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
