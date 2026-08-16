package com.ultidraft.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF3F6B7D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC6E5F3),
    onPrimaryContainer = Color(0xFF00131D),
    secondary = Color(0xFF8A6A32),
    surface = Color(0xFFFCFAF6),
    background = Color(0xFFFCFAF6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FCDE4),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF1C4C5E),
    onPrimaryContainer = Color(0xFFC6E5F3),
    secondary = Color(0xFFE8C88A),
    surface = Color(0xFF101314),
    background = Color(0xFF101314),
)

/** Prose is read, not skimmed: the reader uses a serif face at a generous line height. */
val ReaderTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 19.sp,
        lineHeight = 31.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 22.sp,
        lineHeight = 30.sp,
    ),
)

@Composable
fun UltidraftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = ReaderTypography, content = content)
}
