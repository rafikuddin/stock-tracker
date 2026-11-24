package com.example.stocktracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------- data
data class Session(val token: String, val name: String, val email: String, val role: String = "user")
data class Item(val brand: String, val sku: String, val pcs: Int)
data class SkuStat(val brand: String, val sku: String, val stock: Long)
data class DistStat(
    val region: String, val zone: String, val base: String, val distributor: String,
    val stockSubs: Int, val salesSubs: Int, val totalPcs: Long, val totalSales: Double,
    val last: String, val bu: String, val skus: List<SkuStat>
)

fun bdt(v: Double): String = String.format(Locale.US, "%,.2f", v)
fun whole(v: Long): String = String.format(Locale.US, "%,d", v)
fun compact(v: Double): String = when {
    v >= 1e7 -> String.format(Locale.US, "%.2f Cr", v / 1e7)
    v >= 1e5 -> String.format(Locale.US, "%.2f Lac", v / 1e5)
    v >= 1e3 -> String.format(Locale.US, "%.1fK", v / 1e3)
    else -> String.format(Locale.US, "%.0f", v)
}

fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return if (h < 12) "Good morning" else if (h < 17) "Good afternoon" else "Good evening"
}

// ---- Business units: the BU filter is shown first on every screen
private val BU_ORDER = listOf("Adhesive", "Thinner", "DOP", "Trading-HO")

fun sortBu(list: List<String>): List<String> =
    list.filter { it.isNotBlank() }.distinct()
        .sortedWith(compareBy<String>({ val i = BU_ORDER.indexOf(it); if (i < 0) 99 else i }, { it }))

fun Master.bus(): List<String> = sortBu(distBu.values.toList())

typealias Tree = Map<String, Map<String, Map<String, List<String>>>>

// Region > Zone > Base > Distributor, keeping only the distributors of one BU ("" = all BUs)
fun Master.forBu(bu: String): Tree {
    if (bu.isEmpty()) return regions
    val out = LinkedHashMap<String, Map<String, Map<String, List<String>>>>()
    for ((reg, zones) in regions) {
        val zo = LinkedHashMap<String, Map<String, List<String>>>()
        for ((zone, bases) in zones) {
            val bo = LinkedHashMap<String, List<String>>()
            for ((base, dists) in bases) {
                val keep = dists.filter { distBu["$reg|$zone|$base|$it"] == bu }
                if (keep.isNotEmpty()) bo[base] = keep
            }
            if (bo.isNotEmpty()) zo[zone] = bo
        }
        if (zo.isNotEmpty()) out[reg] = zo
    }
    return out
}

class Loc {
    var bu by mutableStateOf("")
    var region by mutableStateOf(""); var zone by mutableStateOf("")
    var base by mutableStateOf(""); var dist by mutableStateOf("")
    fun key(): String = "$region|$zone|$base|$dist"
    // Picking another BU starts the Region > Zone > Base > Distributor choice again
    fun selectBu(v: String) { if (v == bu) return; bu = v; region = ""; zone = ""; base = ""; dist = "" }
    // After a submit the BU filter stays (people usually work inside one BU)
    fun reset() { region = ""; zone = ""; base = ""; dist = "" }
}

// Entries are kept here (not inside the screen) so switching tabs never loses them
class StockDraft {
    val loc = Loc()
    var brand by mutableStateOf(""); var sku by mutableStateOf(""); var pcs by mutableStateOf("")
    val items = mutableStateListOf<Item>()
}

class SalesDraft {
    val loc = Loc()
    var amount by mutableStateOf("")
}

// ---------------------------------------------------------------- design tokens
private val Bg = Color(0xFFF2F3F8)
private val Ink = Color(0xFF12142A)
private val Muted = Color(0xFF6B6F80)
private val Accent = Color(0xFF4F46E5)
private val AccentSoft = Color(0xFFECEBFF)
private val Soft = Color(0xFFF4F5F9)
private val Line = Color(0xFFE6E8F0)
private val GreenBg = Color(0xFFE6F6EC)
private val Green = Color(0xFF1B9B52)
private val RedBg = Color(0xFFFDECEC)
private val Red = Color(0xFFD33F3F)
private val CardShape = RoundedCornerShape(24.dp)
private val Pill = RoundedCornerShape(50)
private val HeroBrush = Brush.linearGradient(listOf(Color(0xFF3730A3), Color(0xFF6D5CFF)))

// BU colour: (background, text)
fun buColors(bu: String): Pair<Color, Color> = when (bu) {
    "Adhesive" -> Color(0xFFFFEFD2) to Color(0xFFB45309)
    "Thinner" -> Color(0xFFD7F2EE) to Color(0xFF0F766E)
    "DOP" -> Color(0xFFEBE3FF) to Color(0xFF6D28D9)
    "Trading-HO" -> Color(0xFFE2E7F0) to Color(0xFF334155)
    else -> Color(0xFFE8E9EE) to Color(0xFF6B6F80)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Ink, onPrimary = Color.White, background = Bg, surface = Color.White,
                    surfaceTint = Color.Transparent, error = Red
                )
            ) { Surface(Modifier.fillMaxSize(), color = Bg) { Root() } }
        }
    }
}

// ---------------------------------------------------------------- icons (drawn, no icon library needed)
@Composable
fun Icon24(kind: String, color: Color, boxSize: Dp = 24.dp) {
    Canvas(Modifier.size(boxSize)) {
        val u = this.size.width / 24f
        val st = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun pt(x: Float, y: Float) = Offset(x * u, y * u)
        when (kind) {
            "dash" -> {
                val r = CornerRadius(2.5f * u, 2.5f * u)
                for (o in listOf(pt(3.5f, 3.5f), pt(13.5f, 3.5f), pt(3.5f, 13.5f), pt(13.5f, 13.5f)))
                    drawRoundRect(color, o, Size(7f * u, 7f * u), r, style = st)
            }
            "stock" -> {
                drawRoundRect(color, pt(3.5f, 8f), Size(17f * u, 12.5f * u), CornerRadius(3f * u, 3f * u), style = st)
                drawLine(color, pt(3.5f, 13f), pt(20.5f, 13f), strokeWidth = 2f * u, cap = StrokeCap.Round)
                drawLine(color, pt(8f, 3.5f), pt(16f, 3.5f), strokeWidth = 2f * u, cap = StrokeCap.Round)
            }
            "sales" -> {
                drawLine(color, pt(6f, 20f), pt(6f, 13f), strokeWidth = 2.8f * u, cap = StrokeCap.Round)
                drawLine(color, pt(12f, 20f), pt(12f, 8.5f), strokeWidth = 2.8f * u, cap = StrokeCap.Round)
                drawLine(color, pt(18f, 20f), pt(18f, 4f), strokeWidth = 2.8f * u, cap = StrokeCap.Round)
            }
            "chev" -> {
                val p = Path().apply { moveTo(6f * u, 9f * u); lineTo(12f * u, 15f * u); lineTo(18f * u, 9f * u) }
                drawPath(p, color, style = st)
            }
            "check" -> {
                val p = Path().apply { moveTo(5f * u, 12.5f * u); lineTo(10f * u, 17.5f * u); lineTo(19f * u, 7f * u) }
                drawPath(p, color, style = st)
            }
            "close" -> {
                drawLine(color, pt(6f, 6f), pt(18f, 18f), strokeWidth = 2f * u, cap = StrokeCap.Round)
                drawLine(color, pt(18f, 6f), pt(6f, 18f), strokeWidth = 2f * u, cap = StrokeCap.Round)
            }
            "search" -> {
                drawCircle(color, 6.5f * u, pt(10.5f, 10.5f), style = st)
                drawLine(color, pt(15.5f, 15.5f), pt(20f, 20f), strokeWidth = 2f * u, cap = StrokeCap.Round)
            }
            else -> {}
        }
    }
}

// ---------------------------------------------------------------- reusable pieces
@Composable
fun AppCard(modifier: Modifier = Modifier, pad: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(), shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) { Column(Modifier.padding(pad), content = content) }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, dark: Boolean = true) {
    Button(
        onClick = onClick, modifier = modifier.height(50.dp), enabled = enabled, shape = Pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (dark) Ink else Soft, contentColor = if (dark) Color.White else Ink,
            disabledContainerColor = Color(0xFFC9CBD6), disabledContentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 22.dp)
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun StatusChip(text: String, good: Boolean = true) {
    val bg = if (good) GreenBg else Soft
    val fg = if (good) Green else Muted
    Text(text, Modifier.background(bg, Pill).padding(horizontal = 10.dp, vertical = 3.dp), color = fg, fontSize = 11.sp, maxLines = 1)
}

@Composable
fun BuTag(bu: String) {
    val (bg, fg) = buColors(bu)
    Text(bu, Modifier.background(bg, Pill).padding(horizontal = 10.dp, vertical = 3.dp), color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
}

@Composable
fun Chip(text: String, selected: Boolean, accent: Color, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Color.White else Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
        modifier = Modifier.clip(Pill).background(if (selected) accent else Soft).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
    )
}

@Composable
fun BuChips(bus: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("All", selected.isEmpty(), Ink) { onSelect("") }
        bus.forEach { b -> Chip(b, selected == b, buColors(b).second) { onSelect(b) } }
    }
}

@Composable
fun SmallCaps(text: String) {
    Text(text, fontSize = 10.sp, letterSpacing = 1.5.sp, color = Muted, fontWeight = FontWeight.SemiBold)
}

@Composable
fun PageTitle(title: String, sub: String) {
    Column(Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Text(title, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(sub, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun ErrorBox(msg: String) {
    Text(msg, Modifier.fillMaxWidth().background(RedBg, RoundedCornerShape(16.dp)).padding(12.dp), color = Red, fontSize = 13.sp)
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Soft, unfocusedContainerColor = Soft, disabledContainerColor = Color(0xFFEFEFF2),
    focusedBorderColor = Accent, unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent,
    focusedLabelColor = Accent, unfocusedLabelColor = Muted, disabledLabelColor = Color(0xFFA5A5AE),
    disabledTextColor = Ink, cursorColor = Accent
)

@Composable
fun AppField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text, password: Boolean = false
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, modifier = modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(16.dp), colors = fieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None
    )
}

// A tap opens a list you can search - much quicker than a drop-down for 47 brands / 344 SKUs
@Composable
fun PickerField(
    label: String, value: String, options: List<String>, modifier: Modifier = Modifier,
    enabled: Boolean = true, hint: String = "Select", onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Soft else Color(0xFFEDEEF2))
            .clickable(enabled = enabled) { open = true }
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, color = if (enabled) Muted else Color(0xFFA5A5AE))
            Text(
                if (value.isEmpty()) hint else value, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (value.isEmpty()) FontWeight.Normal else FontWeight.Medium,
                color = if (value.isEmpty()) Color(0xFF9A9CAB) else Ink
            )
        }
        Icon24("chev", if (enabled) Muted else Color(0xFFB8BAC6), 22.dp)
    }
    if (open) PickerDialog(label, options, value, { open = false }) { onSelect(it); open = false }
}

@Composable
fun PickerDialog(title: String, options: List<String>, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val shown = remember(q, options) { if (q.isBlank()) options else options.filter { it.contains(q.trim(), ignoreCase = true) } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(max = 560.dp)
                .background(Color.White, RoundedCornerShape(28.dp)).padding(top = 18.dp, bottom = 10.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("${options.size}", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(32.dp).clip(CircleShape).background(Soft).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Icon24("close", Ink, 16.dp)
                }
            }
            if (options.size > 8) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.padding(horizontal = 16.dp)) { AppField(q, { q = it }, "Search $title") }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (shown.isEmpty()) item { Text("Nothing found", color = Muted, modifier = Modifier.padding(20.dp)) }
                items(shown) { o ->
                    val sel = o == selected
                    Row(
                        Modifier.fillMaxWidth().background(if (sel) AccentSoft else Color.Transparent).clickable { onPick(o) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(o, Modifier.weight(1f), fontSize = 15.sp, color = Ink, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        if (sel) Icon24("check", Accent, 20.dp)
                    }
                }
            }
        }
    }
}

@Composable
fun Logo(size: Dp) {
    Box(Modifier.size(size).background(HeroBrush, CircleShape), contentAlignment = Alignment.Center) {
        Text("S", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.5f).sp)
    }
}

// ---------------------------------------------------------------- screens
@Composable
fun Root() {
    var session by remember { mutableStateOf<Session?>(null) }
    val s = session
    if (s == null) LoginScreen { session = it } else HomeScreen(s) { session = null }
}

private val LampBg = Color(0xFF07060A)

@Composable
fun LampArt(lamp: Int, c1: Color, glow: Float, pull: Float, modifier: Modifier) {
    val brass = Color(0xFFC9A24E); val wood = Color(0xFF7A4A32)
    Canvas(modifier) {
        val w = size.width; val h = size.height; val cx = w / 2f
        val top = h * 0.07f; val sb = h * 0.38f
        val topHalf = w * 0.10f; val botHalf = w * 0.22f
        if (glow > 0.01f) {
            val cone = Path().apply {
                moveTo(cx - botHalf, sb); lineTo(cx + botHalf, sb); lineTo(cx + w * 0.60f, h); lineTo(cx - w * 0.60f, h); close()
            }
            drawPath(cone, Brush.verticalGradient(listOf(c1.copy(alpha = 0.40f * glow), Color.Transparent), startY = sb, endY = h))
        }
        val shTop = lerp(Color(0xFF1B1B22), lerp(c1, Color.White, 0.55f), glow)
        val shBot = lerp(Color(0xFF0D0D12), lerp(c1, Color(0xFF7A3A10), 0.25f), glow)
        val shade = Path().apply {
            moveTo(cx - topHalf, top); lineTo(cx + topHalf, top); lineTo(cx + botHalf, sb); lineTo(cx - botHalf, sb); close()
        }
        drawPath(shade, Brush.verticalGradient(listOf(shTop, shBot), startY = top, endY = sb))
        for (i in 0..12) {
            val t = i / 12f
            drawLine(
                Color.Black.copy(alpha = 0.2f), Offset(cx - topHalf + 2 * topHalf * t, top), Offset(cx - botHalf + 2 * botHalf * t, sb),
                strokeWidth = 1.dp.toPx()
            )
        }
        drawOval(
            lerp(Color(0xFF0B0B10), lerp(c1, Color.White, 0.7f), glow),
            Offset(cx - botHalf, sb - h * 0.016f), Size(2 * botHalf, h * 0.032f)
        )
        drawCircle(brass, 4.dp.toPx(), Offset(cx, top - 5.dp.toPx()))
        val hubY = h * 0.80f
        drawRect(brass, Offset(cx - 2.dp.toPx(), sb), Size(4.dp.toPx(), hubY - sb))
        drawRoundRect(brass, Offset(cx - 8.dp.toPx(), hubY), Size(16.dp.toPx(), 18.dp.toPx()), CornerRadius(3.dp.toPx()))
        val legTop = Offset(cx, hubY + 18.dp.toPx())
        drawLine(wood, legTop, Offset(cx - w * 0.17f, h), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(wood, legTop, Offset(cx + w * 0.17f, h), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color(0xFF5A3624), legTop, Offset(cx, h), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
        // pull chain
        val x0 = cx + 10.dp.toPx(); val y0 = sb + 8.dp.toPx(); val len = h * 0.28f + pull.dp.toPx()
        drawLine(
            Color(0xFFC9A85C), Offset(x0, y0), Offset(x0 + 8.dp.toPx(), y0 + len), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 9f))
        )
        drawRoundRect(Color(0xFF8A5A3A), Offset(x0 + 8.dp.toPx() - 3.5.dp.toPx(), y0 + len), Size(7.dp.toPx(), 24.dp.toPx()), CornerRadius(3.5.dp.toPx()))
    }
}

@Composable
fun GlowButton(text: String, enabled: Boolean, c1: Color, c2: Color, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(c1, c2)), alpha = if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color(0xFF2A1405), fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

@Composable
fun darkFieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color.Black.copy(alpha = 0.38f), unfocusedContainerColor = Color.Black.copy(alpha = 0.38f),
    focusedBorderColor = accent, unfocusedBorderColor = Color.White.copy(alpha = 0.08f),
    focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = accent,
    focusedPlaceholderColor = Color.White.copy(alpha = 0.35f), unfocusedPlaceholderColor = Color.White.copy(alpha = 0.35f)
)

@Composable
fun LoginScreen(onLogin: (Session) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("stock", android.content.Context.MODE_PRIVATE) }
    var email by remember { mutableStateOf(prefs.getString("email", "") ?: "") }
    var pass by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf(email.isNotEmpty()) }
    var showPw by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var lamp by remember { mutableIntStateOf(0) }       // 0 off, 1 amber, 2 rose
    var pulled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val lit = lamp > 0
    val c1 by animateColorAsState(if (lamp == 2) Color(0xFFFF9A93) else Color(0xFFFFB347), tween(700), label = "c1")
    val c2 by animateColorAsState(if (lamp == 2) Color(0xFFFF5A4D) else Color(0xFFFF8A1F), tween(700), label = "c2")
    val glow by animateFloatAsState(if (lit) 1f else 0f, tween(900), label = "glow")
    val pull by animateFloatAsState(if (pulled) 16f else 0f, tween(200), label = "pull")
    val bokeh = listOf(Offset(0.15f, 0.10f) to 60f, Offset(0.85f, 0.18f) to 90f, Offset(0.10f, 0.45f) to 70f, Offset(0.92f, 0.55f) to 50f, Offset(0.30f, 0.80f) to 80f, Offset(0.75f, 0.92f) to 64f)

    Box(
        Modifier.fillMaxSize().background(LampBg).drawBehind {
            drawRect(Brush.radialGradient(listOf(c1.copy(alpha = 0.26f * glow), Color.Transparent), center = Offset(size.width * 0.5f, size.height * 0.16f), radius = size.width * 1.2f))
            bokeh.forEach { (p, r) -> drawCircle(c1.copy(alpha = 0.10f * glow), r.dp.toPx(), Offset(size.width * p.x, size.height * p.y)) }
        }
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.fillMaxWidth().height(250.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        pulled = true; lamp = (lamp + 1) % 3
                        scope.launch { delay(220); pulled = false }
                    }
            ) { LampArt(lamp, c1, glow, pull, Modifier.fillMaxSize()) }
            Text(
                if (lit) "PULL AGAIN TO DIM" else "PULL THE CHAIN", color = Color.White.copy(alpha = 0.4f),
                fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(vertical = 8.dp)
            )
            AnimatedVisibility(lit, enter = fadeIn(tween(700)) + slideInVertically(tween(700)) { it / 8 }, exit = fadeOut(tween(300))) {
                val shape = RoundedCornerShape(28.dp)
                Column(
                    Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.11f), Color.White.copy(alpha = 0.04f))), shape)
                        .border(1.dp, Color.White.copy(alpha = 0.14f), shape).padding(20.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("STOCK TRACKER", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "● LIT", color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, letterSpacing = 2.sp,
                            modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), Pill).padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("Welcome back.", color = Color(0xFFF4EFE8), fontSize = 38.sp, fontFamily = FontFamily.Serif, lineHeight = 40.sp)
                    Text(
                        "The light's on for you.", style = TextStyle(
                            brush = Brush.horizontalGradient(listOf(c1, c2)), fontSize = 34.sp, fontStyle = FontStyle.Italic,
                            fontFamily = FontFamily.Serif, lineHeight = 38.sp
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("EMAIL", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp),
                        placeholder = { Text("you@company.com") }, colors = darkFieldColors(c1),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("PASSWORD", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(18.dp),
                        placeholder = { Text("••••••••") }, colors = darkFieldColors(c1),
                        visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton({ showPw = !showPw }) { Text(if (showPw) "Hide" else "Show", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp) } }
                    )
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                keep, { keep = it },
                                colors = SwitchDefaults.colors(
                                    checkedTrackColor = c1, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
                                    uncheckedTrackColor = Color.White.copy(alpha = 0.14f), uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent
                                )
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Remember my email", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                        }
                        Text(
                            "Forgot?", color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.clickable { note = !note }.padding(4.dp)
                        )
                    }
                    if (note) {
                        Text(
                            "Passwords are reset by your administrator.", color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp)).padding(12.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    GlowButton(if (busy) "Please wait..." else "Sign in  →", !busy && email.isNotBlank() && pass.isNotEmpty(), c1, c2) {
                        busy = true; msg = ""
                        scope.launch {
                            try {
                                val r = api("login") { put("email", email.trim()); put("password", pass) }
                                if (keep) prefs.edit().putString("email", email.trim()).apply() else prefs.edit().remove("email").apply()
                                onLogin(Session(r.getString("token"), r.optString("name"), email.trim(), r.optString("role", "user")))
                            } catch (e: Exception) { msg = e.message ?: "Error" }
                            busy = false
                        }
                    }
                    if (msg.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            msg, color = Color(0xFFFFB3B3), fontSize = 13.sp,
                            modifier = Modifier.fillMaxWidth().background(Color(0x33D33F3F), RoundedCornerShape(14.dp)).padding(12.dp)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Accounts are created by your administrator.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- app shell: top bar, bottom navigation
@Composable
fun TopBar(s: Session, display: String, first: String, onLogout: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(38.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Stock Tracker", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Ink)
                Text("Secondary sales & stock", fontSize = 11.sp, color = Muted)
            }
        }
        Box {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Ink).clickable { menu = true },
                contentAlignment = Alignment.Center
            ) { Text(first.take(1).ifEmpty { "U" }, color = Color.White, fontWeight = FontWeight.Bold) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(display, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    Text(s.email, fontSize = 12.sp, color = Muted)
                }
                DropdownMenuItem(text = { Text("Log out", color = Red) }, onClick = { menu = false; onLogout() })
            }
        }
    }
}

@Composable
fun BottomBar(tab: Int, onTab: (Int) -> Unit) {
    val tabs = listOf("Dashboard" to "dash", "Stock" to "stock", "Sales" to "sales")
    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
        tabs.forEachIndexed { i, t ->
            NavigationBarItem(
                selected = tab == i, onClick = { onTab(i) },
                icon = { Icon24(t.second, if (tab == i) Accent else Muted) },
                label = { Text(t.first, fontSize = 12.sp, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = AccentSoft,
                    unselectedIconColor = Muted, unselectedTextColor = Muted
                )
            )
        }
    }
}

@Composable
fun HomeScreen(s: Session, onLogout: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var master by remember { mutableStateOf<Master?>(null) }
    var err by remember { mutableStateOf("") }
    var tries by remember { mutableIntStateOf(0) }
    val stockDraft = remember { StockDraft() }
    val salesDraft = remember { SalesDraft() }
    val display = s.name.ifBlank { s.email.substringBefore("@") }
    val first = display.trim().substringBefore(" ").replaceFirstChar { it.uppercase() }

    LaunchedEffect(tries) {
        err = ""
        try { master = parseMaster(api("master", s.token)) }
        catch (e: SessionExpired) { onLogout() } catch (e: Exception) { err = e.message ?: "Failed to load lists" }
    }
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        TopBar(s, display, first, onLogout)
        val m = master
        Box(Modifier.weight(1f)) {
            if (tab == 0) Dashboard(s, "${greeting()}, $first", onLogout)
            else if (m == null) {
                if (err.isNotEmpty()) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ErrorBox(err)
                        PillButton("Try again", { tries++ })
                    }
                } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            }
            else if (tab == 1) StockScreen(s, m, stockDraft, onLogout) { tab = 0 }
            else SalesScreen(s, m, salesDraft, onLogout) { tab = 0 }
        }
        BottomBar(tab) { tab = it }
    }
}

// ---------------------------------------------------------------- dashboard
@Composable
fun Dashboard(s: Session, title: String, onLogout: () -> Unit) {
    var stats by remember { mutableStateOf<List<DistStat>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var bu by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(reload) {
        loading = true; err = ""
        try {
            val a: JSONArray = api("dashboard", s.token).getJSONArray("data")
            stats = (0 until a.length()).map { a.getJSONObject(it) }.map { d ->
                val sk = d.getJSONArray("skus")
                DistStat(
                    d.getString("region"), d.getString("zone"), d.getString("base"), d.getString("distributor"),
                    d.getInt("stockSubs"), d.getInt("salesSubs"), d.getLong("totalPcs"), d.getDouble("totalSales"),
                    d.optString("last"), d.optString("bu"),
                    (0 until sk.length()).map { sk.getJSONObject(it) }.map { k ->
                        SkuStat(k.getString("brand"), k.getString("sku"), k.getLong("stock"))
                    }
                )
            }
        } catch (e: SessionExpired) { onLogout() } catch (e: Exception) { err = e.message ?: "Error" }
        loading = false
    }
    val buList = remember(stats) { sortBu(stats.map { it.bu }) }
    val inBu = stats.filter { bu.isEmpty() || it.bu == bu }
    val shown = inBu.filter { q.isBlank() || it.distributor.contains(q.trim(), ignoreCase = true) || it.base.contains(q.trim(), ignoreCase = true) }
    val totalStock = inBu.sumOf { it.totalPcs }
    val totalSales = inBu.sumOf { it.totalSales }
    val subs = inBu.sumOf { it.stockSubs + it.salesSubs }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle(title, "Your distributors at a glance.") }
        if (buList.isNotEmpty()) item { BuChips(buList, bu) { bu = it } }
        if (loading) {
            item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) } }
        } else {
            if (err.isNotEmpty()) item { ErrorBox(err) }
            item { HeroCard(if (bu.isEmpty()) "Secondary sales" else "$bu secondary sales", totalSales, totalStock, inBu.size, subs) }
            item { TopDistributors(inBu) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Distributors", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text("${shown.size} shown", fontSize = 12.sp, color = Muted)
                    }
                    PillButton("Refresh", { reload++ }, Modifier.height(40.dp), dark = false)
                }
            }
            item { AppField(q, { q = it }, "Search distributor or base") }
            if (shown.isEmpty()) item { AppCard { Text("No data yet", color = Muted) } }
            items(shown) { d -> DistCard(d, totalSales) }
        }
    }
}

@Composable
fun HeroCard(label: String, sales: Double, stock: Long, dists: Int, subs: Int) {
    Column(Modifier.fillMaxWidth().background(HeroBrush, CardShape).padding(20.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Text(compact(sales), color = Color.White, fontSize = 38.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold)
        Text("BDT ${bdt(sales)}", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp)).padding(vertical = 12.dp)) {
            HeroStat("Stock", "${whole(stock)} pcs", Modifier.weight(1.3f))
            HeroStat("Distributors", "$dists", Modifier.weight(1f))
            HeroStat("Entries", "$subs", Modifier.weight(1f))
        }
    }
}

@Composable
fun HeroStat(label: String, value: String, modifier: Modifier) {
    Column(modifier.padding(horizontal = 14.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun TopDistributors(stats: List<DistStat>) {
    val top = stats.filter { it.totalSales > 0 }.sortedByDescending { it.totalSales }.take(6)
    val max = (top.firstOrNull()?.totalSales ?: 0.0).coerceAtLeast(1.0)
    AppCard {
        Text("Top distributors", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("By secondary sales (BDT)", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        if (top.isEmpty()) {
            Text("No sales submitted yet", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 20.dp))
        } else {
            top.forEachIndexed { i, d ->
                val frac = (d.totalSales / max).toFloat().coerceIn(0.03f, 1f)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", Modifier.width(22.dp), fontSize = 12.sp, color = Muted, fontWeight = FontWeight.SemiBold)
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.distributor, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink)
                            Spacer(Modifier.width(8.dp))
                            Text(compact(d.totalSales), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        }
                        Spacer(Modifier.height(5.dp))
                        Box(Modifier.fillMaxWidth().height(8.dp).background(Soft, Pill)) {
                            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(if (i == 0) Accent else Color(0xFF9D97F5), Pill))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = Muted)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink, maxLines = 1)
    }
}

@Composable
fun DistCard(d: DistStat, totalSales: Double) {
    var open by remember { mutableStateOf(false) }
    val share = if (totalSales > 0) (d.totalSales / totalSales).toFloat() else 0f
    AppCard(Modifier.clip(CardShape).clickable { open = !open }) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(d.distributor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${d.base} · ${d.zone} · ${d.region}", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (d.bu.isNotEmpty()) { Spacer(Modifier.width(8.dp)); BuTag(d.bu) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            Metric("Stock", "${whole(d.totalPcs)} pcs", Modifier.weight(1f))
            Metric("Secondary sales", "BDT ${compact(d.totalSales)}", Modifier.weight(1.2f))
            Metric("Share", "${(share * 100).roundToInt()}%", Modifier.weight(0.6f))
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).background(Soft, Pill)) {
            if (share > 0f) Box(Modifier.fillMaxWidth(share.coerceIn(0.02f, 1f)).fillMaxHeight().background(Accent, Pill))
        }
        Spacer(Modifier.height(8.dp))
        Text("Last: ${d.last.ifEmpty { "-" }}  ·  ${d.stockSubs} stock / ${d.salesSubs} sales entries", fontSize = 11.sp, color = Muted)
        Spacer(Modifier.height(6.dp))
        Text(if (open) "Hide SKU stock ▲" else "Show SKU stock (${d.skus.size}) ▼", fontSize = 12.sp, color = Accent, fontWeight = FontWeight.Medium)
        if (open) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(16.dp)).padding(12.dp)) {
                if (d.skus.isEmpty()) Text("No stock submitted yet", fontSize = 12.sp, color = Muted)
                d.skus.groupBy { it.brand }.forEach { (brand, list) ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text(brand, Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Accent)
                        Text("${whole(list.sumOf { it.stock })} pcs", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Accent)
                    }
                    list.forEach { k ->
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(k.sku, Modifier.weight(1f).padding(end = 8.dp), fontSize = 12.sp)
                            Text(whole(k.stock), fontSize = 12.sp, textAlign = TextAlign.End)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- submit screens
// BU first, then Region > Zone > Base > Distributor. Choosing a BU only offers that BU's distributors.
@Composable
fun LocationCard(m: Master, l: Loc) {
    val bus = remember(m) { m.bus() }
    val view = remember(m, l.bu) { m.forBu(l.bu) }
    var editing by remember { mutableStateOf(false) }
    val collapsed = l.dist.isNotEmpty() && !editing
    AppCard {
        Text("Distributor", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (bus.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SmallCaps("BUSINESS UNIT")
            Spacer(Modifier.height(6.dp))
            BuChips(bus, l.bu) { l.selectBu(it); editing = false }
        }
        Spacer(Modifier.height(14.dp))
        if (collapsed) {
            val b = m.distBu[l.key()] ?: ""
            Row(
                Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(18.dp)).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(l.dist, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${l.base} · ${l.zone} · ${l.region}", fontSize = 12.sp, color = Muted)
                    if (b.isNotEmpty()) { Spacer(Modifier.height(6.dp)); BuTag(b) }
                }
                TextButton({ editing = true }) { Text("Change", color = Accent, fontWeight = FontWeight.SemiBold) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PickerField("Region", l.region, view.keys.toList(), hint = "Select region") { l.region = it; l.zone = ""; l.base = ""; l.dist = "" }
                val zones = view[l.region] ?: emptyMap()
                PickerField("Zone", l.zone, zones.keys.toList(), enabled = l.region.isNotEmpty(), hint = "Select zone") { l.zone = it; l.base = ""; l.dist = "" }
                val bases = zones[l.zone] ?: emptyMap()
                PickerField("Base", l.base, bases.keys.toList(), enabled = l.zone.isNotEmpty(), hint = "Select base") { l.base = it; l.dist = "" }
                PickerField("Distributor", l.dist, bases[l.base] ?: emptyList(), enabled = l.base.isNotEmpty(), hint = "Select distributor") { l.dist = it; editing = false }
            }
        }
    }
}

// Sticky bar at the bottom of the submit screens
@Composable
fun SubmitBar(summary: String, button: String, busy: Boolean, msg: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (msg.isNotEmpty()) { ErrorBox(msg); Spacer(Modifier.height(8.dp)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(summary, Modifier.weight(1f), fontSize = 13.sp, color = Muted, maxLines = 2)
            Spacer(Modifier.width(12.dp))
            PillButton(if (busy) "Submitting..." else button, onClick, enabled = !busy)
        }
    }
}

@Composable
fun StockScreen(s: Session, m: Master, d: StockDraft, onLogout: () -> Unit, onDone: () -> Unit) {
    val loc = d.loc
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // The chosen BU (or, with All, the distributor's own BU) decides which brands/SKUs are offered.
    val bu = if (loc.bu.isNotEmpty()) loc.bu else (m.distBu[loc.key()] ?: "")
    val brandMap = m.buBrands[bu] ?: m.brands
    val brand = if (brandMap.containsKey(d.brand)) d.brand else ""
    val skuList = brandMap[brand] ?: emptyList()
    val sku = if (skuList.contains(d.sku)) d.sku else ""
    val pcsNum = d.pcs.toIntOrNull() ?: 0
    val canAdd = brand.isNotEmpty() && sku.isNotEmpty() && pcsNum > 0

    fun addItem() {
        val newItem = Item(brand, sku, pcsNum)
        val at = d.items.indexOfFirst { x -> x.brand == brand && x.sku == sku }
        if (at >= 0) d.items[at] = newItem else d.items.add(newItem)   // same SKU again = replace its pcs
        d.sku = ""; d.pcs = ""
    }

    fun submit() {
        if (loc.dist.isEmpty() || d.items.isEmpty()) { msg = "Select a distributor and add at least one item"; return }
        busy = true; msg = ""
        scope.launch {
            try {
                api("submit_stock", s.token) {
                    put("region", loc.region); put("zone", loc.zone); put("base", loc.base); put("distributor", loc.dist)
                    put("items", JSONArray(d.items.map { JSONObject().put("brand", it.brand).put("sku", it.sku).put("pcs", it.pcs) }))
                }
                loc.reset(); d.items.clear(); d.brand = ""; d.sku = ""; d.pcs = ""
                android.widget.Toast.makeText(ctx, "Stock submitted", android.widget.Toast.LENGTH_SHORT).show()
                onDone()
            } catch (e: SessionExpired) { onLogout() } catch (e: Exception) { msg = e.message ?: "Failed" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PageTitle("Submit stock", "SKU-wise stock for one distributor.") }
            item { LocationCard(m, loc) }
            item {
                AppCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Add SKU", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        if (bu.isNotEmpty()) BuTag(bu)
                    }
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PickerField("Brand", brand, brandMap.keys.toList(), hint = "Select brand") { d.brand = it; d.sku = "" }
                        PickerField("SKU", sku, skuList, enabled = brand.isNotEmpty(), hint = "Select SKU") { d.sku = it }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppField(d.pcs, { d.pcs = it.filter(Char::isDigit) }, "Pcs", Modifier.weight(1f), keyboard = KeyboardType.Number)
                            PillButton("+ Add", { addItem() }, Modifier.height(56.dp), enabled = canAdd)
                        }
                    }
                }
            }
            if (d.items.isNotEmpty()) item {
                AppCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Items (${d.items.size})", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        StatusChip("${whole(d.items.sumOf { it.pcs.toLong() })} pcs total")
                    }
                    Spacer(Modifier.height(8.dp))
                    d.items.toList().forEach { i ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Soft, RoundedCornerShape(14.dp)).padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(i.sku, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Text("${i.brand}  ·  ${i.pcs} pcs", fontSize = 11.sp, color = Muted)
                            }
                            Box(Modifier.size(44.dp).clickable { d.items.remove(i) }, contentAlignment = Alignment.Center) { Icon24("close", Red, 18.dp) }
                        }
                    }
                }
            }
        }
        SubmitBar(
            if (d.items.isEmpty()) "No items yet" else "${d.items.size} items · ${whole(d.items.sumOf { it.pcs.toLong() })} pcs",
            "Submit stock", busy, msg
        ) { submit() }
    }
}

@Composable
fun SalesScreen(s: Session, m: Master, d: SalesDraft, onLogout: () -> Unit, onDone: () -> Unit) {
    val loc = d.loc
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val amt = d.amount.toDoubleOrNull() ?: 0.0

    fun submit() {
        if (loc.dist.isEmpty() || amt <= 0.0) { msg = "Select a distributor and enter the sales amount"; return }
        busy = true; msg = ""
        scope.launch {
            try {
                api("submit_sales", s.token) {
                    put("region", loc.region); put("zone", loc.zone); put("base", loc.base); put("distributor", loc.dist); put("amount", amt)
                }
                loc.reset(); d.amount = ""
                android.widget.Toast.makeText(ctx, "Sales submitted", android.widget.Toast.LENGTH_SHORT).show()
                onDone()
            } catch (e: SessionExpired) { onLogout() } catch (e: Exception) { msg = e.message ?: "Failed" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { PageTitle("Submit sales", "Secondary sales for one distributor, in BDT.") }
            item { LocationCard(m, loc) }
            item {
                AppCard {
                    Text("Secondary sales", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    AppField(d.amount, { d.amount = it.filter { c -> c.isDigit() || c == '.' } }, "Amount (BDT)", keyboard = KeyboardType.Decimal)
                    if (amt > 0.0) {
                        Spacer(Modifier.height(10.dp))
                        StatusChip("BDT ${bdt(amt)}  ·  ${compact(amt)}")
                    }
                }
            }
        }
        SubmitBar(
            if (loc.dist.isEmpty()) "Select a distributor" else if (amt > 0.0) "BDT ${bdt(amt)}" else "Enter the amount",
            "Submit sales", busy, msg
        ) { submit() }
    }
}
