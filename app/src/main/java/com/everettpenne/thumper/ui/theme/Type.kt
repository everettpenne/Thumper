package com.everettpenne.thumper.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.everettpenne.thumper.R

/** Inter (SIL OFL 1.1, https://github.com/rsms/inter) as one bundled variable font: every weight from one file, no download, the same face on every phone. */
@OptIn(ExperimentalTextApi::class)
val InterFamily = FontFamily(
    listOf(400, 500, 600, 700).map { weight ->
        Font(R.font.inter_variable, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
    }
)

/**
 * Apple's HIG type scale (SF Pro sizes, line heights and tracking) set in Inter. Screens take their text from here, directly or through the
 * Material mapping below, rather than inventing sizes. Sizes are sp so they follow the system font scale; tracking is in em so it scales with size.
 *
 * | Token       | Size | Weight | Line | Tracking |
 * | largeTitle  | 34   | 400    | 41   | +0.011   |
 * | title1      | 28   | 400    | 34   | +0.013   |
 * | title2      | 22   | 400    | 28   | +0.016   |
 * | title3      | 20   | 400    | 25   | +0.019   |
 * | headline    | 17   | 600    | 22   | -0.024   |
 * | body        | 17   | 400    | 22   | -0.024   |
 * | callout     | 16   | 400    | 21   | -0.020   |
 * | subheadline | 15   | 400    | 20   | -0.016   |
 * | footnote    | 13   | 400    | 18   | -0.006   |
 * | caption1    | 12   | 400    | 16   | 0        |
 * | caption2    | 11   | 400    | 13   | +0.007   |
 *
 * iOS is Regular nearly everywhere and Semibold for Headline; the medium and semibold forms for compact labels and buttons come from
 * `copy(fontWeight = ...)` on a token, so a weight change stays anchored to the same size and rhythm.
 */
object IosType {
    private fun style(size: Int, lineHeight: Int, trackingEm: Double, weight: Int = 400) = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = lineHeight.sp, letterSpacing = trackingEm.em)

    val largeTitle = style(34, 41, 0.011)
    val title1 = style(28, 34, 0.013)
    val title2 = style(22, 28, 0.016)
    val title3 = style(20, 25, 0.019)
    val headline = style(17, 22, -0.024, weight = 600)
    val body = style(17, 22, -0.024)
    val callout = style(16, 21, -0.020)
    val subheadline = style(15, 20, -0.016)
    val footnote = style(13, 18, -0.006)
    val caption1 = style(12, 16, 0.0)
    val caption2 = style(11, 13, 0.007)
}

/** The scale on Material's slots, by the role each plays here: headlineMedium page title, titleLarge dialog title, titleMedium section/post title, bodyLarge body, bodySmall help line. */
internal val ThumperTypography = Typography(
    displayLarge = IosType.largeTitle,
    displayMedium = IosType.title1,
    displaySmall = IosType.title2,
    headlineLarge = IosType.title3,
    headlineMedium = IosType.title1,
    headlineSmall = IosType.title2,
    titleLarge = IosType.title3.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = IosType.headline,
    titleSmall = IosType.subheadline.copy(fontWeight = FontWeight.Medium),
    bodyLarge = IosType.body,
    bodyMedium = IosType.callout,
    bodySmall = IosType.footnote,
    labelLarge = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = IosType.caption1.copy(fontWeight = FontWeight.Medium),
    labelSmall = IosType.caption2.copy(fontWeight = FontWeight.Medium),
)
