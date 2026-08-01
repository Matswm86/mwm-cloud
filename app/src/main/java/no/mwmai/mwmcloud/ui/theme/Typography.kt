package no.mwmai.mwmcloud.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import no.mwmai.mwmcloud.R

/**
 * Both faces ship as single variable-font files rather than one static file per
 * weight: Google Fonts publishes no statics for either, and one 400 KB file beats
 * six. Variation settings need API 26, which is our minSdk.
 *
 * Bricolage Grotesque carries three axes (opsz, wdth, wght). Only weight varies;
 * optical size is pinned to the display end and width to normal, so headings look
 * the same as the mockups regardless of rendered size.
 */
@OptIn(ExperimentalTextApi::class)
private fun bricolage(weight: Int) = Font(
    resId = R.font.bricolage_grotesque_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.opticalSizing(14.sp),
        FontVariation.width(100f),
    ),
)

@OptIn(ExperimentalTextApi::class)
private fun figtree(weight: Int) = Font(
    resId = R.font.figtree_variable,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Headings. The design specifies 600 and 700 only. */
val Heading = FontFamily(bricolage(600), bricolage(700))

/** Body. The design specifies 400, 500 and 600 only. */
val Body = FontFamily(figtree(400), figtree(500), figtree(600))

/**
 * Sizes follow the graphics pack. Nothing here may drop below
 * [MwmDimens.MinTextSize] (15 sp) — that floor is a legibility requirement, not a
 * preference, and [MwmTypographyTest] fails the build if it is broken.
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
