package com.surveycontroller.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// 延续桌面端 Fluent 主色调（青蓝），并支持 Material You 动态取色
private val BrandPrimary = Color(0xFF0067C0)
private val BrandPrimaryDark = Color(0xFF4CC2FF)

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFF4F6071),
    tertiary = Color(0xFF6750A4),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEEF1F6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F6FA),
    surfaceContainer = Color(0xFFEEF1F6),
    surfaceContainerHigh = Color(0xFFE7EBF1),
    outlineVariant = Color(0xFFD6DCE5),
)

private val DarkColors = darkColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = Color(0xFF00345C),
    primaryContainer = Color(0xFF004A7C),
    onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFFB8C8DA),
    tertiary = Color(0xFFCFBCFF),
    background = Color(0xFF111418),
    surface = Color(0xFF161A1F),
    surfaceVariant = Color(0xFF222831),
    surfaceContainerLowest = Color(0xFF0E1115),
    surfaceContainerLow = Color(0xFF161A1F),
    surfaceContainer = Color(0xFF1A1F25),
    surfaceContainerHigh = Color(0xFF242A31),
    outlineVariant = Color(0xFF3A424C),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SurveyControllerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
