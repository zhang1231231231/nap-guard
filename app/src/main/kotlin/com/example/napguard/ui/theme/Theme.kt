package com.example.napguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = WeChatGreen,
    onPrimary = Color.White,
    primaryContainer = WeChatGreenLight,
    onPrimaryContainer = WeChatGreenDark,
    secondary = iOS_SecondaryLabel,
    onSecondary = Color.White,
    background = iOS_Background,
    onBackground = iOS_Label,
    surface = iOS_SecondaryBackground,
    onSurface = iOS_Label,
    surfaceVariant = iOS_TertiaryBackground,
    onSurfaceVariant = iOS_SecondaryLabel,
    outline = iOS_Separator,
    outlineVariant = iOS_TertiaryLabel,
)

private val DarkColorScheme = darkColorScheme(
    primary = WeChatGreen,
    onPrimary = Color.White,
    primaryContainer = WeChatGreenDark,
    onPrimaryContainer = WeChatGreenLight,
    secondary = iOS_Dark_SecondaryLabel,
    onSecondary = Color.White,
    background = iOS_Dark_Background,
    onBackground = iOS_Dark_Label,
    surface = iOS_Dark_SecondaryBackground,
    onSurface = iOS_Dark_Label,
    surfaceVariant = iOS_Dark_TertiaryBackground,
    onSurfaceVariant = iOS_Dark_SecondaryLabel,
)

@Composable
fun NapGuardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
