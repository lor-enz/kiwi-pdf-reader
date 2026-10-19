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
    primary = OceanBlue,
    onPrimary = OffWhite,
    primaryContainer = IceBlue,
    onPrimaryContainer = DeepBlue,
    secondary = OceanBlue,
    onSecondary = OffWhite,
    secondaryContainer = IceBlue,
    onSecondaryContainer = DeepBlue,
    error = ErrorOrange,
    onError = NightBackground,
    background = OffWhite,
    onBackground = Charcoal,
    surface = OffWhite,
    onSurface = Charcoal,
    surfaceVariant = IceBlue,
    onSurfaceVariant = SlateGrey,
    outline = FieldBorder,
    outlineVariant = Gallery,
    inverseSurface = NightBackground,
    inverseOnSurface = NightText,
    inversePrimary = PenguinBlue,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = OffWhite,
    surfaceContainer = IceBlue,
    surfaceContainerHigh = IceBlue,
    surfaceContainerHighest = IceBlue,
)

// Penguin Blue for text and accents; filled buttons use Deep Blue instead.
private val DarkColorScheme = darkColorScheme(
    primary = PenguinBlue,
    onPrimary = NightBackground,
    primaryContainer = DeepBlue,
    onPrimaryContainer = IceBlue,
    secondary = PenguinBlue,
    onSecondary = NightBackground,
    secondaryContainer = DeepBlue,
    onSecondaryContainer = IceBlue,
    error = ErrorOrange,
    onError = NightBackground,
    background = NightBackground,
    onBackground = NightText,
    surface = NightSurface,
    onSurface = NightText,
    surfaceVariant = NightContainer,
    onSurfaceVariant = NightSlateGrey,
    outline = FieldBorder,
    outlineVariant = NightBorder,
    inverseSurface = NightText,
    inverseOnSurface = Charcoal,
    inversePrimary = OceanBlue,
    // Bottom sheets (surfaceContainerLow) sit darker than the background.
    surfaceContainerLowest = NightSheet,
    surfaceContainerLow = NightSheet,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightContainer,
    surfaceContainerHighest = NightContainer,
)

/**
 * The fill of filled buttons (see [PrimaryButton]). Deep Blue in dark mode: a blue bright enough
 * for text on the dark background glares as a filled area.
 */
internal val LocalButtonColor = staticCompositionLocalOf { OceanBlue }

// No dynamic (wallpaper) colors on purpose: the app is always blue.
@Composable
fun PdfPenguinTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = Shapes,
    ) {
        CompositionLocalProvider(LocalButtonColor provides if (darkTheme) DeepBlue else OceanBlue, content = content)
    }
}
