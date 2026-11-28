package app.kitna.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.kitna.core.Group
import app.kitna.core.GroupAppearance

/** The same palette is saved in the book as stable color IDs, never theme colors. */
internal fun groupColor(key: String): Color = Color(
    GroupAppearance.colors[key] ?: GroupAppearance.colors.getValue("teal")
)

/** Black/white choose the stronger text contrast while preserving each saved group color. */
internal fun groupInk(fill: Color): Color = if (fill.luminance() > .179f) Color.Black else Color.White

/** Individual symbols stay code-native and sharp at every Android density. */
@Composable internal fun GroupSymbol(
    icon: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Canvas(modifier.size(24.dp)) { drawBookSymbol(icon, color) }
}

@Composable internal fun GroupStylePreview(icon: String, color: String, size: Int = 56) {
    val fill = groupColor(color)
    val ink = groupInk(fill)
    Box(
        Modifier.size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp))
            .background(fill)
            .border(1.5.dp,MaterialTheme.colorScheme.onSurface,RoundedCornerShape((size / 4).dp))
            .semantics { contentDescription = "$icon icon, $color group color" },
        contentAlignment = Alignment.Center
    ) {
        GroupSymbol(icon, Modifier.size((size * .56f).dp), ink)
    }
}

@Composable internal fun GroupAvatar(group: Group, size: Int = 48) {
    GroupStylePreview(group.icon, group.color, size)
}

@Composable internal fun GroupHeader(group: Group, description: String) {
    val fill = groupColor(group.color)
    val ink = groupInk(fill)
    Column(Modifier.fillMaxWidth().background(fill).drawBehind {
        val w=size.width;val h=size.height
        // Diagonal manga shades increase contrast with the chosen ink rather than washing it out.
        val shade = if (ink == Color.White) Color.Black else Color.White
        drawPath(Path().apply { moveTo(w*.42f,0f);lineTo(w,h*.5f);lineTo(w*.7f,h);lineTo(w*.1f,h);close() },shade.copy(alpha=.05f))
        drawPath(Path().apply { moveTo(w*.7f,0f);lineTo(w*.3f,h);lineTo(w,h);close() },shade.copy(alpha=.08f))
    }.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        GroupSymbol(group.icon,Modifier.size(34.dp),ink)
        Text(group.name,style=MaterialTheme.typography.headlineLarge,color=ink)
        Text("${group.kind} · ${group.members.size} people",style=MaterialTheme.typography.bodyMedium,color=ink)
        Text(description,style=MaterialTheme.typography.bodySmall,color=ink,maxLines=2)
    }
}

internal fun categoryAppearance(category: String, dark: Boolean): Pair<String, Color> {
    val (symbol, base) = when (category.lowercase()) {
        "food", "food and drinks" -> "food" to Color(0xFFB12F5A)
        "groceries" -> "groceries" to Color(0xFF246647)
        "travel", "transport", "transportation" -> "car" to Color(0xFF245FAD)
        "rent" -> "home" to Color(0xFF6846A8)
        "accommodation" -> "home" to Color(0xFF4163AA)
        "entertainment" -> "ticket" to Color(0xFF9B3F97)
        "utilities" -> "bolt" to Color(0xFF3457A4)
        "shopping" -> "shopping" to Color(0xFF7851AE)
        "health" -> "health" to Color(0xFFB4234A)
        "other" -> "receipt" to Color(0xFF526681)
        else -> "receipt" to Color(0xFF007561)
    }
    return symbol to if (dark) lerp(base, Color.White, .4f) else base
}

@Composable internal fun CategoryIcon(
    category: String,
    modifier: Modifier = Modifier,
    size: Int = 40
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .3f
    val (symbol, ink) = categoryAppearance(category, dark)
    Box(
        modifier.size(size.dp)
            .clip(RoundedCornerShape((size / 4).dp))
            .background(ink.copy(alpha = if (dark) .14f else .10f))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        GroupSymbol(symbol, Modifier.size((size * .60f).dp), ink)
    }
}

/** App mark: a K-shaped shared ledger with two payment branches. */
@Composable internal fun KitnaMark(modifier: Modifier = Modifier, size: Int = 48) {
    val fill = MaterialTheme.colorScheme.primaryContainer
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    val secondaryInk = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(size.dp).clip(RoundedCornerShape((size * 24f / 108f).dp)).semantics { contentDescription = "Kitna logo" }) {
        val s = this.size.width / 108f
        drawRoundRect(fill, cornerRadius = CornerRadius(12f * s))
        val accent = Path().apply {
            moveTo(0f, 88f * s); lineTo(108f * s, 24f * s)
            lineTo(108f * s, 108f * s); lineTo(0f, 108f * s); close()
        }
        drawPath(accent, secondaryInk.copy(alpha = .12f))
        fun branch(x: Float, y: Float, a: Float, b: Float, color: Color, width: Float) {
            drawLine(color, Offset(x * s, y * s), Offset(a * s, b * s), width * s, StrokeCap.Round)
        }
        branch(35f, 35f, 35f, 73f, ink, 9f)
        branch(41f, 54f, 66f, 34f, ink, 8f)
        branch(41f, 54f, 66f, 74f, secondaryInk, 8f)
        drawCircle(ink, 5f * s, Offset(41f * s, 54f * s))
        drawCircle(ink, 6f * s, Offset(69f * s, 32f * s))
        drawCircle(secondaryInk, 6f * s, Offset(69f * s, 76f * s))
    }
}

private fun DrawScope.drawBookSymbol(icon: String, color: Color) {
    val s = size.width / 24f
    val stroke = Stroke(width = 1.8f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x: Float, y: Float, a: Float, b: Float) =
        drawLine(color, Offset(x * s, y * s), Offset(a * s, b * s), stroke.width, StrokeCap.Round)
    fun circle(x: Float, y: Float, r: Float, fill: Boolean = false) {
        if (fill) drawCircle(color, r * s, Offset(x * s, y * s))
        else drawCircle(color, r * s, Offset(x * s, y * s), style = stroke)
    }
    fun path(vararg points: Pair<Float, Float>, close: Boolean = false) {
        val p = Path().apply {
            points.forEachIndexed { i, point ->
                if (i == 0) moveTo(point.first * s, point.second * s)
                else lineTo(point.first * s, point.second * s)
            }
            if (close) close()
        }
        drawPath(p, color, style = stroke)
    }
    fun box(x: Float, y: Float, width: Float, height: Float, radius: Float = 1.5f) =
        drawRoundRect(color, Offset(x * s, y * s), Size(width * s, height * s), CornerRadius(radius * s), style = stroke)
    when (icon) {
        "group", "groups" -> {
            circle(8.5f, 7f, 3f); circle(17.5f, 8f, 2.3f)
            path(2.5f to 20f, 2.5f to 17f, 5.5f to 13.5f, 11.5f to 13.5f, 14.5f to 17f, 14.5f to 20f)
            path(17.5f to 14f, 21f to 17f, 21f to 20f)
        }
        "home" -> {
            path(2f to 11f, 12f to 3f, 22f to 11f)
            path(5f to 9f, 5f to 21f, 19f to 21f, 19f to 9f)
            path(10f to 21f, 10f to 14f, 14f to 14f, 14f to 21f)
        }
        "plane" -> {
            path(12f to 2f, 14f to 10f, 22f to 14f, 22f to 16f, 14f to 14f, 14f to 19f, 17f to 21f, 17f to 22f, 12f to 21f, 7f to 22f, 7f to 21f, 10f to 19f, 10f to 14f, 2f to 16f, 2f to 14f, 10f to 10f, close = true)
        }
        "car" -> {
            path(4f to 10f, 6f to 5f, 18f to 5f, 20f to 10f)
            box(3f, 10f, 18f, 8f, 2f); line(6f, 18f, 6f, 21f); line(18f, 18f, 18f, 21f)
            circle(7f, 14f, 1f, true); circle(17f, 14f, 1f, true)
        }
        "food" -> {
            circle(12f, 12f, 6f)
            line(2f, 3f, 2f, 9f); line(5f, 3f, 5f, 9f); line(2f, 9f, 5f, 9f)
            line(3.5f, 9f, 3.5f, 21f)
            path(22f to 3f, 20f to 10f, 22f to 10f, 22f to 21f)
        }
        "groceries" -> {
            path(4f to 9f, 20f to 9f, 18f to 21f, 6f to 21f, close = true)
            path(7f to 9f, 9f to 3f); path(17f to 9f, 15f to 3f)
            line(9f, 12f, 10f, 18f); line(15f, 12f, 14f, 18f)
        }
        "shopping" -> {
            box(4f, 8f, 16f, 13f)
            drawArc(color, 180f, 180f, false, Offset(8f * s, 3f * s), Size(8f * s, 10f * s), style = stroke)
        }
        "heart" -> {
            val p = Path().apply {
                moveTo(12f * s, 21f * s)
                cubicTo(8f * s, 17f * s, 2f * s, 12f * s, 2f * s, 7.5f * s)
                cubicTo(2f * s, 2f * s, 9f * s, 1.5f * s, 12f * s, 6f * s)
                cubicTo(15f * s, 1.5f * s, 22f * s, 2f * s, 22f * s, 7.5f * s)
                cubicTo(22f * s, 12f * s, 16f * s, 17f * s, 12f * s, 21f * s)
                close()
            }; drawPath(p, color, style = stroke)
        }
        "work" -> {
            box(2f, 7f, 20f, 14f); box(8f, 3f, 8f, 4f, .5f)
            line(2f, 13f, 22f, 13f); line(10f, 13f, 14f, 13f)
            line(12f, 12f, 12f, 15f)
        }
        "beach" -> {
            drawArc(color, 180f, 180f, false, Offset(2f * s, 3f * s), Size(20f * s, 18f * s), style = stroke)
            line(2f, 12f, 22f, 12f); line(12f, 12f, 12f, 21f); line(8f, 21f, 16f, 21f)
            path(12f to 3f, 8f to 12f); path(12f to 3f, 16f to 12f)
        }
        "gift" -> {
            box(3f, 8f, 18f, 4f, .7f); box(5f, 12f, 14f, 9f, .7f); line(12f, 8f, 12f, 21f)
            val p = Path().apply { moveTo(12f*s,8f*s); cubicTo(1f*s,8f*s,5f*s,-1f*s,12f*s,8f*s); cubicTo(19f*s,-1f*s,23f*s,8f*s,12f*s,8f*s) }
            drawPath(p,color,style=stroke)
        }
        "games" -> {
            val p=Path().apply {moveTo(7f*s,7f*s);lineTo(17f*s,7f*s);cubicTo(21f*s,7f*s,23f*s,20f*s,19f*s,20f*s);lineTo(15f*s,16f*s);lineTo(9f*s,16f*s);lineTo(5f*s,20f*s);cubicTo(1f*s,20f*s,3f*s,7f*s,7f*s,7f*s);close()}
            drawPath(p,color,style=stroke);line(6f,11f,10f,11f);line(8f,9f,8f,13f);circle(16f,10f,.8f,true);circle(18f,13f,.8f,true)
        }
        "coffee" -> {
            path(4f to 8f, 4f to 18f, 16f to 18f, 16f to 8f, close=true)
            drawArc(color,-90f,180f,false,Offset(13f*s,9f*s),Size(8f*s,7f*s),style=stroke)
            line(2f,21f,19f,21f);line(7f,2f,7f,5f);line(12f,2f,12f,5f)
        }
        "ticket" -> {
            path(3f to 5f,21f to 5f,21f to 10f,19f to 12f,21f to 14f,21f to 19f,3f to 19f,3f to 14f,5f to 12f,3f to 10f,close=true)
            line(15f,8f,15f,10f);line(15f,14f,15f,16f)
        }
        "bolt" -> path(14f to 2f,4f to 14f,11f to 14f,10f to 22f,21f to 9f,14f to 9f,close=true)
        "health" -> path(9f to 3f,15f to 3f,15f to 9f,21f to 9f,21f to 15f,15f to 15f,15f to 21f,9f to 21f,9f to 15f,3f to 15f,3f to 9f,9f to 9f,close=true)
        else -> {
            path(5f to 3f,19f to 3f,19f to 21f,15f to 19f,12f to 21f,8f to 19f,5f to 21f,close=true)
            line(8f,8f,16f,8f);line(8f,12f,16f,12f)
        }
    }
}
