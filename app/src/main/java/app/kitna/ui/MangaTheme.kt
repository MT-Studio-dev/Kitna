package app.kitna.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// Paired indigo and slate surfaces retain the ink-panel identity without a warm paper tint.
private val MangaLight = lightColorScheme(
    primary = Color(0xFF3448B5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E9FF), onPrimaryContainer = Color(0xFF182B68),
    secondary = Color(0xFF52617B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EAF3), onSecondaryContainer = Color(0xFF1B2A43),
    tertiary = Color(0xFF006B60), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8F3EC), onTertiaryContainer = Color(0xFF004D44),
    background = Color(0xFFF7F9FC), onBackground = Color(0xFF182235),
    surface = Color.White, onSurface = Color(0xFF182235),
    surfaceVariant = Color(0xFFE8EDF5), onSurfaceVariant = Color(0xFF475569),
    surfaceDim = Color(0xFFDCE3EE), surfaceBright = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F4FA),
    surfaceContainer = Color(0xFFEBEFF7), surfaceContainerHigh = Color(0xFFE4EAF3),
    surfaceContainerHighest = Color(0xFFDCE3EE), surfaceTint = Color(0xFF3448B5),
    outline = Color(0xFF68778D), outlineVariant = Color(0xFFC8D1E0),
    inverseSurface = Color(0xFF273247), inverseOnSurface = Color(0xFFF1F5FB), inversePrimary = Color(0xFFB8C4FF),
    error = Color(0xFFB42335), onError = Color.White,
    errorContainer = Color(0xFFFDE7EB), onErrorContainer = Color(0xFF731B2A), scrim = Color.Black
)
private val MangaDark = darkColorScheme(
    primary = Color(0xFFB9C5FF), onPrimary = Color(0xFF182763),
    primaryContainer = Color(0xFF2C3D82), onPrimaryContainer = Color(0xFFE0E6FF),
    secondary = Color(0xFFB7C7E5), onSecondary = Color(0xFF20304A),
    secondaryContainer = Color(0xFF2D3E59), onSecondaryContainer = Color(0xFFDFE9FA),
    tertiary = Color(0xFF82D9C6), onTertiary = Color(0xFF003A32),
    tertiaryContainer = Color(0xFF064E45), onTertiaryContainer = Color(0xFFBBF2E5),
    background = Color(0xFF101722), onBackground = Color(0xFFE6EDF7),
    surface = Color(0xFF172131), onSurface = Color(0xFFE6EDF7),
    surfaceVariant = Color(0xFF2D3A4D), onSurfaceVariant = Color(0xFFBBC8DC),
    surfaceDim = Color(0xFF101722), surfaceBright = Color(0xFF354258),
    surfaceContainerLowest = Color(0xFF0B111B), surfaceContainerLow = Color(0xFF131D2C),
    surfaceContainer = Color(0xFF1B2739), surfaceContainerHigh = Color(0xFF233147),
    surfaceContainerHighest = Color(0xFF2B3A51), surfaceTint = Color(0xFFB9C5FF),
    outline = Color(0xFF8798B2), outlineVariant = Color(0xFF43526A),
    inverseSurface = Color(0xFFE6EDF7), inverseOnSurface = Color(0xFF243247), inversePrimary = Color(0xFF3448B5),
    error = Color(0xFFFFB3BC), onError = Color(0xFF650E22),
    errorContainer = Color(0xFF7A2132), onErrorContainer = Color(0xFFFFE3E8), scrim = Color.Black
)
internal fun kitnaColorScheme(dark: Boolean): ColorScheme = if (dark) MangaDark else MangaLight
internal fun balanceColor(owed: Boolean, dark: Boolean): Color = when {
    owed && dark -> Color(0xFF8ADDC9)
    owed -> Color(0xFF006B59)
    dark -> MangaDark.error
    else -> MangaLight.error
}
private val MangaTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.ExtraBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 16.sp, lineHeight = 23.sp),
        bodySmall = base.bodySmall.copy(fontSize = 14.sp, lineHeight = 21.sp),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelSmall = base.labelSmall.copy(fontSize = 14.sp, lineHeight = 20.sp)
    )
}

@Composable fun MangaTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = kitnaColorScheme(dark), typography = MangaTypography,
        shapes = Shapes(RoundedCornerShape(2.dp), RoundedCornerShape(4.dp), RoundedCornerShape(6.dp), RoundedCornerShape(8.dp), RoundedCornerShape(12.dp)),
        content = content)
}

internal enum class BudgetMood(val label: String) {
    Comfortable("On track"), Watch("Watch spending"), Near("Near limit"), Critical("Almost at limit"), Used("Budget used"), Over("Over budget")
}
internal fun budgetMood(spent: Long, budget: Long): BudgetMood {
    require(budget > 0 && spent >= 0)
    val ratio = spent.toDouble() / budget
    return when {
        spent > budget -> BudgetMood.Over
        spent == budget -> BudgetMood.Used
        ratio >= .95 -> BudgetMood.Critical
        ratio >= .80 -> BudgetMood.Near
        ratio >= .60 -> BudgetMood.Watch
        else -> BudgetMood.Comfortable
    }
}
internal fun BudgetMood.ink(dark: Boolean): Color = when (this) {
    BudgetMood.Comfortable -> balanceColor(owed = true, dark = dark)
    BudgetMood.Watch -> if (dark) Color(0xFFB9C8FF) else Color(0xFF375AB5)
    BudgetMood.Near -> if (dark) Color(0xFFD9BCFF) else Color(0xFF7546AD)
    else -> kitnaColorScheme(dark).error
}
@Composable internal fun BudgetMood.ink(): Color = ink(MaterialTheme.colorScheme.surface.luminance() < .3f)

internal enum class ExpenseEmphasis { Small, Medium, Large }
internal fun expenseEmphasis(amount: Long, budget: Long?, largest: Long): ExpenseEmphasis {
    require(amount >= 0 && largest >= 0)
    val baseline = budget?.takeIf { it > 0 } ?: largest.takeIf { it > 0 } ?: return ExpenseEmphasis.Small
    val ratio = amount.toDouble() / baseline
    val largeThreshold = if (budget != null && budget > 0) .20 else .60
    val mediumThreshold = if (budget != null && budget > 0) .05 else .20
    return when { ratio >= largeThreshold -> ExpenseEmphasis.Large; ratio >= mediumThreshold -> ExpenseEmphasis.Medium; else -> ExpenseEmphasis.Small }
}

@Composable internal fun MangaPanel(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface, content: @Composable ColumnScope.() -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier.padding(end = 4.dp, bottom = 4.dp).drawBehind {
        drawRoundRect(ink, topLeft = Offset(4.dp.toPx(), 4.dp.toPx()), cornerRadius = CornerRadius(4.dp.toPx()))
    }) {
        Surface(color = color, shape = RoundedCornerShape(4.dp), border = BorderStroke(2.dp, ink)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable internal fun MangaChapter(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primaryContainer
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Box(modifier.fillMaxWidth().clipToBounds()) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(accent)
            val origin = Offset(size.width * 1.15f, size.height * .65f)
            repeat(16) { index ->
                val angle = (index * 360f / 16) * Math.PI.toFloat() / 180f
                drawLine(ink.copy(alpha = .08f), origin,
                    Offset(origin.x + cos(angle) * size.width * 2f, origin.y + sin(angle) * size.width * 2f), 12.dp.toPx())
            }
        }
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = ink)
            Text(subtitle, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, color = ink)
        }
    }
}
