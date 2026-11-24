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

private val MangaLight = lightColorScheme(
    primary = Color(0xFFA63C17), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD080), onPrimaryContainer = Color(0xFF382313),
    secondary = Color(0xFF754A24), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE7BA), onSecondaryContainer = Color(0xFF382313),
    tertiary = Color(0xFF6E478F), onTertiary = Color.White,
    background = Color(0xFFFFF7E8), onBackground = Color(0xFF282128),
    surface = Color(0xFFFFFDF6), onSurface = Color(0xFF282128),
    surfaceVariant = Color(0xFFF3E8D7), onSurfaceVariant = Color(0xFF61564F),
    outline = Color(0xFF766B63), outlineVariant = Color(0xFFD4C4AD),
    error = Color(0xFFAD2C39), onError = Color.White,
    errorContainer = Color(0xFFFFE2E5), onErrorContainer = Color(0xFF6C1422)
)
private val MangaDark = darkColorScheme(
    primary = Color(0xFFFFC56B), onPrimary = Color(0xFF382313),
    primaryContainer = Color(0xFF5B3D25), onPrimaryContainer = Color(0xFFFFE4BA),
    secondary = Color(0xFFE4B68F), onSecondary = Color(0xFF382313),
    secondaryContainer = Color(0xFF45352B), onSecondaryContainer = Color(0xFFFFE6CA),
    tertiary = Color(0xFFD8B9EB), onTertiary = Color(0xFF392245),
    background = Color(0xFF1D191D), onBackground = Color(0xFFFFF0D9),
    surface = Color(0xFF282228), onSurface = Color(0xFFFFF0D9),
    surfaceVariant = Color(0xFF3A3036), onSurfaceVariant = Color(0xFFDFCFBE),
    outline = Color(0xFFB7A697), outlineVariant = Color(0xFF61534E),
    error = Color(0xFFFFADB6), onError = Color(0xFF5D1221),
    errorContainer = Color(0xFF542431), onErrorContainer = Color(0xFFFFDCE1)
)
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
    MaterialTheme(colorScheme = if (dark) MangaDark else MangaLight, typography = MangaTypography,
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
@Composable internal fun BudgetMood.ink(): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .3f
    return when (this) {
        BudgetMood.Comfortable -> if (dark) Color(0xFF99DFB4) else Color(0xFF17613D)
        BudgetMood.Watch -> if (dark) Color(0xFFF2D17C) else Color(0xFF805000)
        BudgetMood.Near -> if (dark) Color(0xFFFFB793) else Color(0xFF9A3F15)
        else -> MaterialTheme.colorScheme.error
    }
}

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
