package kiwi.lorenz.pdfreader.ui.theme

import androidx.compose.ui.graphics.Color

// Penguin Blue is the app icon's background and the accent in dark mode. Too light for text on a
// light background (2.6:1), so light mode uses Ocean Blue, the same hue darker (5:1).
val PenguinBlue = Color(0xFF00A6FB)
val OceanBlue = Color(0xFF0070AA)
val DeepBlue = Color(0xFF00507A)
val IceBlue = Color(0xFFE1F2FC)

// Errors (a wrong password): orange instead of Material's red, the same in both themes. Only 3:1
// for text on the light background, accepted on purpose: a darker orange looked too dark.
val ErrorOrange = Color(0xFFF46036)

val Charcoal = Color(0xFF333333)
val OffWhite = Color(0xFFF8FAFC)
val Gallery = Color(0xFFEFEFEF)

val NightBackground = Color(0xFF14181C)
val NightSurface = Color(0xFF1C2126)
val NightContainer = Color(0xFF262D33)
val NightText = Color(0xFFE2E8ED)
val NightSheet = Color(0xFF0E1114)

// Secondary text and borders that need contrast (at least 4.5:1 for text, 3:1 for field borders)
val SlateGrey = Color(0xFF56616B)
val NightSlateGrey = Color(0xFFAEB8C1)
val NightBorder = Color(0xFF333B42)
val FieldBorder = Color(0xFF87929C)
