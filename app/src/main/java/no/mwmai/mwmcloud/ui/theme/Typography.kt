package no.mwmai.mwmcloud.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The design specifies Bricolage Grotesque 600/700 for headings and Figtree
// 400/500/600 for body. Both are SIL OFL and get bundled as font resources in
// the design-system task; until those files land, weights and sizes are correct
// and only the face falls back to the platform default.
private val Heading = FontFamily.Default
private val Body = FontFamily.Default

/**
 * Sizes follow the graphics pack. Nothing here may drop below
 * [MwmDimens.MinTextSize] (15 sp) — that floor is a legibility requirement,
 * not a preference.
 */
val MwmTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Heading,
        fontWeight = FontWeight.W700,
        fontSize = 40.sp,
        lineHeight = 46.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Heading,
        fontWeight = FontWeight.W700,
        fontSize = 34.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Heading,
        fontWeight = FontWeight.W600,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Heading,
        fontWeight = FontWeight.W600,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.W400,
        fontSize = 19.sp,
        lineHeight = 28.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.W400,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.W600,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Body,
        fontWeight = FontWeight.W500,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
)
