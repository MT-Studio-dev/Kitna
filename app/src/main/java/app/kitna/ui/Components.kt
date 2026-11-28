package app.kitna.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kitna.core.*
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

internal fun money(value: Long): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).format(BigDecimal.valueOf(value, 2))
internal fun Book.personName(id: String) = people.firstOrNull { it.id == id }?.name ?: "Deleted person"
internal fun Book.groupName(id: String) = if(id.isEmpty()) "Personal book" else groups.firstOrNull { it.id == id }?.let { if(it.kind=="Direct") "Non-group expenses" else it.name } ?: "Group"
internal val Owe: Color
    @Composable get() = balanceColor(owed = false, dark = MaterialTheme.colorScheme.surface.luminance() < .3f)
internal val Owed: Color
    @Composable get() = balanceColor(owed = true, dark = MaterialTheme.colorScheme.surface.luminance() < .3f)
internal val Categories = listOf("General", "Food", "Groceries", "Travel", "Rent", "Entertainment", "Utilities", "Shopping", "Health", "Other")

@Composable internal fun Glyph(name: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier.size(24.dp)) {
        val scale = size.width / 24f
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, Offset(x * scale, y * scale), Offset(a * scale, b * scale), 1.8f * scale, StrokeCap.Round)
        fun circle(x: Float, y: Float, r: Float) = drawCircle(color, r * scale, Offset(x * scale, y * scale), style = Stroke(1.7f * scale))
        when (name) {
            "add" -> { line(5f,12f,19f,12f); line(12f,5f,12f,19f) }
            "close" -> { line(6f,6f,18f,18f); line(18f,6f,6f,18f) }
            "back" -> { line(5f,12f,20f,12f); line(5f,12f,11f,6f); line(5f,12f,11f,18f) }
            "search" -> { circle(10f,10f,6f); line(15f,15f,21f,21f) }
            "friends", "account" -> { circle(12f,7f,3.5f); drawArc(color, 185f, 170f, false, Offset(4f*scale,12f*scale), androidx.compose.ui.geometry.Size(16f*scale,13f*scale), style=Stroke(1.8f*scale)) }
            "groups" -> { circle(9f,7f,3f); circle(18f,8f,2.3f); line(3f,20f,3f,17f); line(3f,17f,6f,13f); line(6f,13f,12f,13f); line(12f,13f,15f,17f); line(15f,17f,15f,20f); line(18f,14f,21f,17f); line(21f,17f,21f,20f) }
            "activity" -> { line(3f,13f,7f,13f); line(7f,13f,10f,5f); line(10f,5f,14f,20f); line(14f,20f,18f,10f); line(18f,10f,22f,10f) }
            "report" -> { line(5f,20f,5f,12f); line(12f,20f,12f,4f); line(19f,20f,19f,8f) }
            "filter" -> { line(3f,6f,21f,6f); line(6f,12f,18f,12f); line(10f,18f,14f,18f) }
            "chevron" -> { line(9f,6f,15f,12f); line(15f,12f,9f,18f) }
            "expand" -> { line(7f,9f,12f,14f);line(12f,14f,17f,9f) }
            "collapse" -> { line(7f,14f,12f,9f);line(12f,9f,17f,14f) }
            "settings" -> { circle(12f,12f,4f);circle(12f,12f,8f);line(12f,2f,12f,4f);line(12f,20f,12f,22f);line(2f,12f,4f,12f);line(20f,12f,22f,12f) }
            "check" -> { line(4f,12f,10f,18f); line(10f,18f,21f,6f) }
            "share" -> { circle(5f,12f,2.5f); circle(18f,5f,2.5f); circle(18f,19f,2.5f); line(7.5f,10.5f,15.5f,6.5f); line(7.5f,13.5f,15.5f,17.5f) }
            "mail" -> { line(3f,5f,21f,5f); line(21f,5f,21f,19f); line(21f,19f,3f,19f); line(3f,19f,3f,5f); line(3f,6f,12f,13f); line(12f,13f,21f,6f) }
            "calendar" -> { line(4f,5f,20f,5f); line(20f,5f,20f,21f); line(20f,21f,4f,21f); line(4f,21f,4f,5f); line(4f,10f,20f,10f); line(8f,3f,8f,7f); line(16f,3f,16f,7f) }
            "help" -> { circle(12f,12f,9f);drawArc(color,190f,280f,false,Offset(9f*scale,6f*scale),androidx.compose.ui.geometry.Size(6f*scale,6f*scale),style=Stroke(1.8f*scale));line(12f,12f,12f,14f);circle(12f,18f,.4f) }
            "download" -> { line(12f,3f,12f,16f); line(7f,11f,12f,16f); line(12f,16f,17f,11f); line(4f,19f,20f,19f) }
            "edit" -> { line(4f,20f,9f,19f); line(4f,20f,5f,15f); line(5f,15f,16f,4f); line(16f,4f,20f,8f); line(20f,8f,9f,19f) }
            else -> { val path=Path().apply {moveTo(5f*scale,3f*scale);lineTo(19f*scale,3f*scale);lineTo(19f*scale,21f*scale);lineTo(16f*scale,19f*scale);lineTo(12f*scale,21f*scale);lineTo(8f*scale,19f*scale);lineTo(5f*scale,21f*scale);close()};drawPath(path,color,style=Stroke(1.7f*scale));line(8f,8f,16f,8f);line(8f,12f,16f,12f) }
        }
    }
}
@Composable internal fun ActionIcon(name: String, description: String, onClick: () -> Unit) { IconButton(onClick, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = description }) { Glyph(name, color = LocalContentColor.current) } }
@Composable internal fun Avatar(name: String, group: Boolean = false, size: Int = 48) {
    val palette=listOf(Color(0xFFE5F3EF),Color(0xFFE5EBFA),Color(0xFFECEAF8),Color(0xFFE8F0FC))
    Box(Modifier.size(size.dp).clip(if(group) RoundedCornerShape(12.dp) else CircleShape).background(palette[(name.hashCode() and Int.MAX_VALUE)%palette.size]),contentAlignment=Alignment.Center) {
        if(group) Glyph("groups",color=Color(0xFF4A7569)) else Text(name.trim().split(" ").filter {it.isNotEmpty()}.take(2).joinToString(""){it.take(1).uppercase()},color=Color(0xFF326F60),fontSize=(size/3).sp,fontWeight=FontWeight.SemiBold)
    }
}
@Composable internal fun EmptyState(title: String, body: String, action: String?=null, onAction:()->Unit={}) {
    Column(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=40.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(80.dp).background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(5.dp)).border(2.dp,MaterialTheme.colorScheme.onSurface,RoundedCornerShape(5.dp)),contentAlignment=Alignment.Center){Glyph("receipt",Modifier.size(40.dp),MaterialTheme.colorScheme.onPrimaryContainer)}
        Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
        Text(body,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        if(action!=null)OutlinedButton(onAction,Modifier.heightIn(min=48.dp)){Text(action)}
    }
}
@Composable internal fun BalanceText(net:Long) { val color=if(net<0)Owe else if(net>0)Owed else MaterialTheme.colorScheme.onSurfaceVariant;Column(horizontalAlignment=Alignment.End){Text(if(net<0)"you owe" else if(net>0)"owes you" else "no net balance",style=MaterialTheme.typography.bodySmall,color=color);if(net!=0L)Text(money(kotlin.math.abs(net)),color=color,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)} }
