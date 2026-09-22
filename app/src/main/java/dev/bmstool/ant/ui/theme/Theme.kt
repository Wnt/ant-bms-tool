package dev.bmstool.ant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF66BB6A)
val Amber = Color(0xFFFFB300)
val Red = Color(0xFFEF5350)
val Blue = Color(0xFF42A5F5)
val Surface = Color(0xFF101418)
val SurfaceHigh = Color(0xFF1B2128)

private val DarkScheme = darkColorScheme(
    primary = Green,
    secondary = Blue,
    tertiary = Amber,
    error = Red,
    background = Surface,
    surface = Surface,
    surfaceVariant = SurfaceHigh,
    onPrimary = Color.Black,
    onBackground = Color(0xFFE6EAEE),
    onSurface = Color(0xFFE6EAEE),
)

@Composable
fun AntBmsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
