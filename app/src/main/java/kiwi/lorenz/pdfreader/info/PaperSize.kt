package kiwi.lorenz.pdfreader.info

import kotlin.math.abs

/** Common paper sizes, portrait, in millimeters. */
enum class Paper(val label: String, val width: Double, val height: Double) {
    A3("A3", 297.0, 420.0),
    A4("A4", 210.0, 297.0),
    A5("A5", 148.0, 210.0),
    A6("A6", 105.0, 148.0),
    B4("B4", 250.0, 353.0),
    B5("B5", 176.0, 250.0),
    LETTER("Letter", 215.9, 279.4),
    LEGAL("Legal", 215.9, 355.6),
    TABLOID("Tabloid", 279.4, 431.8),
}

data class NamedSize(val paper: Paper, val isLandscape: Boolean)

val PageSize.widthMm get() = width * MM_PER_POINT
val PageSize.heightMm get() = height * MM_PER_POINT

/** The paper size this page has, allowing for rounding by the app that made it. */
fun PageSize.paper(): NamedSize? {
    val isLandscape = width > height
    val short = if (isLandscape) heightMm else widthMm
    val long = if (isLandscape) widthMm else heightMm
    val paper = Paper.entries.firstOrNull {
        abs(it.width - short) <= TOLERANCE_MM && abs(it.height - long) <= TOLERANCE_MM
    } ?: return null
    return NamedSize(paper, isLandscape)
}

private const val MM_PER_POINT = 25.4 / 72
private const val TOLERANCE_MM = 2.0
