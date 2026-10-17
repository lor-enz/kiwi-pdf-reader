package kiwi.lorenz.pdfreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Surface containers (sheets, dialogs, menus) are set too, or they turn Material's default lavender.
private val LightColorScheme = lightColorScheme(
    primary = KiwiGreen,
    onPrimary = OffWhite,
    primaryContainer = LightGreen,
    onPrimaryContainer = DarkGreen,
    secondary = KiwiBrown,
    onSecondary = OffWhite,
    secondaryContainer = LightBrown,
    onSecondaryContainer = DarkBrown,
    background = OffWhite,
    onBackground = Charcoal,
    surface = OffWhite,
    onSurface = Charcoal,
    surfaceVariant = LightGreen,
    onSurfaceVariant = Charcoal,
    outline = Gallery,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = OffWhite,
    surfaceContainer = LightGreen,
    surfaceContainerHigh = LightGreen,
    surfaceContainerHighest = LightGreen,
)

private val DarkColorScheme = darkColorScheme(
    primary = PaleGreen,
    onPrimary = NightBackground,
    primaryContainer = DarkGreen,
    onPrimaryContainer = LightGreen,
    secondary = KiwiBrown,
    onSecondary = NightBackground,
    background = NightBackground,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    onSurfaceVariant = NightText,
    surfaceContainerLowest = NightBackground,
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightContainer,
    surfaceContainerHighest = NightContainer,
)

// No dynamic (wallpaper) colors on purpose: the app is always green.
@Composable
fun KiwiPDFReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
