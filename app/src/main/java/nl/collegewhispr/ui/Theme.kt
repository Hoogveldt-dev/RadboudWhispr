package nl.collegewhispr.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The iOS system palette (grouped-list variant), light and dark. */
@Immutable
data class IosColors(
    val background: Color,
    val card: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val accent: Color,
    val red: Color,
    val green: Color,
    val orange: Color,
)

private val Light = IosColors(
    background = Color(0xFFF2F2F7),
    card = Color.White,
    label = Color.Black,
    secondaryLabel = Color(0x993C3C43),
    tertiaryLabel = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43),
    fill = Color(0x1F767680),
    accent = Color(0xFF007AFF),
    red = Color(0xFFFF3B30),
    green = Color(0xFF34C759),
    orange = Color(0xFFFF9500),
)

private val Dark = IosColors(
    background = Color.Black,
    card = Color(0xFF1C1C1E),
    label = Color.White,
    secondaryLabel = Color(0x99EBEBF5),
    tertiaryLabel = Color(0x4DEBEBF5),
    separator = Color(0x99545458),
    fill = Color(0x3D767680),
    accent = Color(0xFF0A84FF),
    red = Color(0xFFFF453A),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
)

val LocalIos = staticCompositionLocalOf { Light }

/** Text sizes after the iOS type scale. */
object IosType {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp)
    val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.2).sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.1).sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val ios = if (isSystemInDarkTheme()) Dark else Light
    val base = if (ios === Dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = ios.accent,
        onPrimary = Color.White,
        secondary = ios.accent,
        error = ios.red,
        background = ios.background,
        onBackground = ios.label,
        surface = ios.card,
        onSurface = ios.label,
        surfaceVariant = ios.fill,
        onSurfaceVariant = ios.secondaryLabel,
        surfaceContainer = ios.card,
        surfaceContainerHigh = ios.card,
        surfaceContainerHighest = ios.card,
        surfaceContainerLow = ios.card,
        outline = ios.separator,
        outlineVariant = ios.separator,
    )
    val type = Typography(
        bodyLarge = IosType.body,
        bodyMedium = IosType.subhead,
        bodySmall = IosType.footnote,
        titleLarge = IosType.title2,
        titleMedium = IosType.headline,
        labelLarge = IosType.subhead.copy(fontWeight = FontWeight.Medium),
        labelMedium = IosType.footnote,
    )
    // Menus use the extra-small shape; iOS menus and alerts are clearly rounded.
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(14.dp),
        extraLarge = RoundedCornerShape(14.dp),
    )
    CompositionLocalProvider(LocalIos provides ios) {
        MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
    }
}
