package com.wsolv.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors =
    lightColorScheme(
        primary = WordleGreen,
        secondary = WordleYellow,
    )

private val DarkColors =
    darkColorScheme(
        primary = WordleGreen,
        secondary = WordleYellow,
    )

/**
 * App-wide Material 3 theme. Follows the system light/dark setting unless
 * [darkTheme] is overridden (e.g. by previews).
 */
@Composable
fun WsolvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = WsolvTypography,
        content = content,
    )
}
