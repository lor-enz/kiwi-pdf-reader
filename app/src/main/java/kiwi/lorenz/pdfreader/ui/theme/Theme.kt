package kiwi.lorenz.pdfreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Every role a component may use is set, or it turns Material's default lavender: surface
// containers (sheets, dialogs, menus), inverse colors (snackbars). Outline is for field borders,
// outlineVariant for decorative ones (cards).
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
    onSurfaceVariant = OliveGrey,
    outline = FieldBorder,
    outlineVariant = Gallery,
    inverseSurface = NightBackground,
    inverseOnSurface = NightText,
    inversePrimary = KiwiGreen,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = OffWhite,
    surfaceContainer = LightGreen,
    surfaceContainerHigh = LightGreen,
    surfaceContainerHighest = LightGreen,
)

// The same green as in light mode for text and accents; filled buttons use Dark Green instead.
private val DarkColorScheme = darkColorScheme(
    primary = KiwiGreen,
    onPrimary = OffWhite,
    primaryContainer = DarkGreen,
    onPrimaryContainer = LightGreen,
    secondary = KiwiBrown,
    onSecondary = NightBackground,
    secondaryContainer = DarkBrown,
    onSecondaryContainer = LightBrown,
    background = NightBackground,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightContainer,
    onSurfaceVariant = NightOliveGrey,
    outline = FieldBorder,
    outlineVariant = NightBorder,
    inverseSurface = NightText,
    inverseOnSurface = Charcoal,
    inversePrimary = DarkGreen,
    // Bottom sheets (surfaceContainerLow) sit darker than the background.
    surfaceContainerLowest = NightSheet,
    surfaceContainerLow = NightSheet,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightContainer,
    surfaceContainerHighest = NightContainer,
)

/**
 * The fill of filled buttons (see [PrimaryButton]). Dark Green in dark mode: a green bright
 * enough for text on the dark background glares as a filled area.
 */
internal val LocalButtonColor = staticCompositionLocalOf { KiwiGreen }

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
    ) {
        CompositionLocalProvider(LocalButtonColor provides if (darkTheme) DarkGreen else KiwiGreen, content = content)
    }
}
