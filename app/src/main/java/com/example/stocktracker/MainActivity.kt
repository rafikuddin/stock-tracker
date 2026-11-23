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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

// ---------------------------------------------------------------- data
data class Session(val token: String, val name: String, val email: String)
data class Item(val brand: String, val sku: String, val pcs: Int)
data class SkuStat(val brand: String, val sku: String, val stock: Long)
data class DistStat(
    val region: String, val zone: String, val base: String, val distributor: String,
    val stockSubs: Int, val salesSubs: Int, val totalPcs: Long, val totalSales: Double,
    val last: String, val skus: List<SkuStat>
)

fun bdt(v: Double): String = String.format(Locale.US, "%,.2f", v)
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

class Loc {
    var region by mutableStateOf(""); var zone by mutableStateOf("")
    var base by mutableStateOf(""); var dist by mutableStateOf("")
    fun reset() { region = ""; zone = ""; base = ""; dist = "" }
}

// ---------------------------------------------------------------- design tokens
private val Bg = Color(0xFFF1F1F4)
private val Ink = Color(0xFF111111)
private val Muted = Color(0xFF6E6E78)
private val Accent = Color(0xFF4F46E5)
private val Soft = Color(0xFFF6F6F8)
private val GreenBg = Color(0xFFE6F6EC)
private val Green = Color(0xFF1B9B52)
private val RedBg = Color(0xFFFDECEC)
private val Red = Color(0xFFD33F3F)
private val CardShape = RoundedCornerShape(24.dp)
private val Pill = RoundedCornerShape(50)
private val HeroBrush = Brush.linearGradient(listOf(Color(0xFF4338CA), Color(0xFF6D5CFF)))

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
            disabledContainerColor = Color(0xFFC9C9D1), disabledContentColor = Color.White
        ),
        contentPadding = PaddingValues(horizontal = 20.dp)
    ) { Text(text, fontWeight = FontWeight.Medium) }
}

@Composable
fun StatusChip(text: String, onDark: Boolean = false, good: Boolean = true) {
    val bg = if (onDark) Color.White.copy(alpha = 0.2f) else if (good) GreenBg else Soft
    val fg = if (onDark) Color.White else if (good) Green else Muted
    Text(text, Modifier.background(bg, Pill).padding(horizontal = 10.dp, vertical = 3.dp), color = fg, fontSize = 11.sp, maxLines = 1)
}

@Composable
fun StatTile(title: String, value: String, sub: String, modifier: Modifier = Modifier, hero: Boolean = false) {
    val shape = RoundedCornerShape(22.dp)
    val bgMod = if (hero) Modifier.background(HeroBrush, shape) else Modifier.background(Color.White, shape)
    Column(modifier.then(bgMod).padding(16.dp)) {
        Text(title, color = if (hero) Color.White.copy(alpha = 0.8f) else Muted, fontSize = 13.sp, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Text(value, color = if (hero) Color.White else Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        StatusChip(sub, onDark = hero)
    }
}

@Composable
fun PageTitle(title: String, sub: String) {
    Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text(title, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, color = Ink)
        Text(sub, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dropdown(label: String, options: List<String>, selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var exp by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = exp, onExpandedChange = { if (enabled) exp = !exp }) {
        OutlinedTextField(
            value = selected, onValueChange = {}, readOnly = true, enabled = enabled, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(exp) },
            shape = RoundedCornerShape(16.dp), colors = fieldColors(),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = exp, onDismissRequest = { exp = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); exp = false }) }
        }
    }
}

@Composable
fun Logo(size: Dp) {
    Box(Modifier.size(size).background(HeroBrush, CircleShape), contentAlignment = Alignment.Center) {
        Text("S", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.5f).sp)
    }
}

@Composable
fun HatchedBar(modifier: Modifier, solid: Boolean) {
    Canvas(modifier) {
        val r = 8.dp.toPx()
        val shape = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r))) }
        clipPath(shape) {
            drawRect(Accent.copy(alpha = if (solid) 1f else 0.9f))
            if (!solid) {
                var x = -size.height
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = 0.4f), Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 2.dp.toPx())
                    x += 7.dp.toPx()
                }
            }
        }
    }
}

@Composable
fun Ring(fraction: Float, label: String, modifier: Modifier = Modifier) {
    Box(modifier.size(58.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 6.dp.toPx()
            val topLeft = Offset(sw / 2, sw / 2)
            val arcSize = Size(size.width - sw, size.height - sw)
            drawArc(Color(0xFFE9E9EE), 0f, 360f, false, topLeft, arcSize, style = Stroke(sw))
            drawArc(Accent, -90f, 360f * fraction.coerceIn(0f, 1f), false, topLeft, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink)
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
                                onLogin(Session(r.getString("token"), r.optString("name"), email.trim()))
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

@Composable
fun HomeScreen(s: Session, onLogout: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var master by remember { mutableStateOf<Master?>(null) }
    var err by remember { mutableStateOf("") }
    val display = s.name.ifBlank { s.email.substringBefore("@") }
    val first = display.trim().substringBefore(" ").replaceFirstChar { it.uppercase() }

    LaunchedEffect(Unit) {
        try { master = parseMaster(api("master", s.token)) }
        catch (e: SessionExpired) { onLogout() } catch (e: Exception) { err = e.message ?: "Failed to load lists" }
    }
    Column(Modifier.fillMaxSize().background(Bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(Modifier.background(Color.White, Pill).padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo(32.dp); Spacer(Modifier.width(8.dp))
                Text("Stock Tracker", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            Row(
                Modifier.clip(Pill).background(Color.White).clickable(onClick = onLogout).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(32.dp).background(Ink, CircleShape), contentAlignment = Alignment.Center) {
                    Text(first.take(1), color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
                Text("Logout", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
        val labels = listOf("Dashboard", "Stock", "Sales")
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(Color.White, Pill).padding(4.dp)) {
            labels.forEachIndexed { i, l ->
                val sel = i == tab
                Box(
                    Modifier.weight(1f).height(40.dp).clip(Pill).background(if (sel) Ink else Color.Transparent).clickable { tab = i },
                    contentAlignment = Alignment.Center
                ) { Text(l, color = if (sel) Color.White else Ink, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
            }
        }
        if (err.isNotEmpty()) Box(Modifier.padding(16.dp)) { ErrorBox(err) }
        val m = master
        Box(Modifier.weight(1f)) {
            if (tab == 0) Dashboard(s, "${greeting()}, $first", onLogout)
            else if (m == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) }
            else if (tab == 1) StockScreen(s, m, onLogout) { tab = 0 }
            else SalesScreen(s, m, onLogout) { tab = 0 }
        }
    }
}

@Composable
fun Dashboard(s: Session, title: String, onLogout: () -> Unit) {
    var stats by remember { mutableStateOf<List<DistStat>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        loading = true; err = ""
        try {
            val a: JSONArray = api("dashboard", s.token).getJSONArray("data")
            stats = (0 until a.length()).map { a.getJSONObject(it) }.map { d ->
                val sk = d.getJSONArray("skus")
                DistStat(
                    d.getString("region"), d.getString("zone"), d.getString("base"), d.getString("distributor"),
                    d.getInt("stockSubs"), d.getInt("salesSubs"), d.getLong("totalPcs"), d.getDouble("totalSales"), d.optString("last"),
                    (0 until sk.length()).map { sk.getJSONObject(it) }.map { k ->
                        SkuStat(k.getString("brand"), k.getString("sku"), k.getLong("stock"))
                    }
                )
            }
        } catch (e: SessionExpired) { onLogout() } catch (e: Exception) { err = e.message ?: "Error" }
        loading = false
    }
    val totalStock = stats.sumOf { it.totalPcs }
    val totalSales = stats.sumOf { it.totalSales }
    val subs = stats.sumOf { it.stockSubs + it.salesSubs }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle(title, "Track stock and secondary sales for every distributor.") }
        if (loading) {
            item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Accent) } }
        } else {
            if (err.isNotEmpty()) item { ErrorBox(err) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile("Total Stock", "%,d".format(Locale.US, totalStock), "pcs", Modifier.weight(1f), hero = true)
                        StatTile("Secondary Sales", compact(totalSales), "BDT", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StatTile("Distributors", "${stats.size}", "active", Modifier.weight(1f))
                        StatTile("Submissions", "$subs", "all time", Modifier.weight(1f))
                    }
                }
            }
            item { SalesChart(stats) }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Distributors", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("Ring = share of total secondary sales", fontSize = 12.sp, color = Muted)
                    }
                    PillButton("Refresh", { reload++ }, Modifier.height(40.dp), dark = false)
                }
            }
            if (stats.isEmpty()) item { AppCard { Text("No data yet", color = Muted) } }
            items(stats) { d -> DistCard(d, totalSales) }
        }
    }
}

@Composable
fun SalesChart(stats: List<DistStat>) {
    val top = stats.filter { it.totalSales > 0 }.sortedByDescending { it.totalSales }.take(6)
    val max = (top.firstOrNull()?.totalSales ?: 0.0).coerceAtLeast(1.0)
    AppCard {
        Text("Secondary Sales", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Top distributors by BDT", color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        if (top.isEmpty()) {
            Text("No sales submitted yet", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 24.dp))
        } else {
            Row(
                Modifier.fillMaxWidth().height(170.dp).background(Soft, RoundedCornerShape(18.dp)).padding(10.dp),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                top.forEachIndexed { i, d ->
                    val h = (100f * (d.totalSales / max).toFloat()).coerceAtLeast(6f)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(compact(d.totalSales), fontSize = 10.sp, color = Muted, maxLines = 1)
                        Spacer(Modifier.height(4.dp))
                        HatchedBar(Modifier.fillMaxWidth(0.7f).height(h.dp), solid = i == 0)
                        Spacer(Modifier.height(6.dp))
                        Text(d.distributor, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Ink)
                    }
                }
            }
        }
    }
}

@Composable
fun DistCard(d: DistStat, totalSales: Double) {
    var open by remember { mutableStateOf(false) }
    val share = if (totalSales > 0) (d.totalSales / totalSales).toFloat() else 0f
    AppCard(Modifier.clip(CardShape).clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.distributor, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${d.region} > ${d.zone} > ${d.base}", fontSize = 12.sp, color = Muted)
            }
            Ring(share, "${(share * 100).roundToInt()}%")
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("Stock ${d.totalPcs} pcs")
            StatusChip("BDT ${bdt(d.totalSales)}")
        }
        Spacer(Modifier.height(6.dp))
        Text("${d.stockSubs} stock / ${d.salesSubs} sales submissions  |  Last: ${d.last}", fontSize = 11.sp, color = Muted)
        Spacer(Modifier.height(8.dp))
        Text(if (open) "Hide SKU stock ▲" else "Show SKU stock ▼", fontSize = 12.sp, color = Accent, fontWeight = FontWeight.Medium)
        if (open) {
            Spacer(Modifier.height(8.dp))
            Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(16.dp)).padding(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("Brand / SKU", Modifier.weight(2f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Stock (pcs)", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 12.sp, textAlign = TextAlign.End)
                }
                if (d.skus.isEmpty()) Text("No stock submitted yet", fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
                d.skus.forEach { k ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Text("${k.brand} / ${k.sku}", Modifier.weight(2f), fontSize = 12.sp)
                        Text("${k.stock}", Modifier.weight(1f), fontSize = 12.sp, textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}

@Composable
fun LocationPicker(m: Master, l: Loc) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Dropdown("Region", m.regions.keys.toList(), l.region) { l.region = it; l.zone = ""; l.base = ""; l.dist = "" }
        val zones = m.regions[l.region] ?: emptyMap()
        Dropdown("Zone", zones.keys.toList(), l.zone, l.region.isNotEmpty()) { l.zone = it; l.base = ""; l.dist = "" }
        val bases = zones[l.zone] ?: emptyMap()
        Dropdown("Base", bases.keys.toList(), l.base, l.zone.isNotEmpty()) { l.base = it; l.dist = "" }
        Dropdown("Distributor", bases[l.base] ?: emptyList(), l.dist, l.base.isNotEmpty()) { l.dist = it }
    }
}

@Composable
fun StockScreen(s: Session, m: Master, onLogout: () -> Unit, onDone: () -> Unit) {
    val loc = remember { Loc() }
    var brand by remember { mutableStateOf("") }
    var sku by remember { mutableStateOf("") }
    var pcs by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<Item>() }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { PageTitle("Submit Stock", "Record SKU-wise stock for a distributor.") }
        item {
            AppCard {
                Text("Distributor", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                LocationPicker(m, loc)
            }
        }
        item {
            AppCard {
                Text("Add Stock (SKU wise)", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dropdown("Brand", m.brands.keys.toList(), brand) { brand = it; sku = "" }
                    Dropdown("SKU", m.brands[brand] ?: emptyList(), sku, brand.isNotEmpty()) { sku = it }
                    AppField(pcs, { pcs = it.filter(Char::isDigit) }, "Pcs", keyboard = KeyboardType.Number)
                    PillButton("+ Add Item", {
                        val n = pcs.toIntOrNull() ?: 0
                        if (brand.isNotEmpty() && sku.isNotEmpty() && n > 0) { items.add(Item(brand, sku, n)); sku = ""; pcs = "" }
                    }, Modifier.fillMaxWidth(), dark = false)
                }
            }
        }
        if (items.isNotEmpty()) item {
            AppCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Items (${items.size})", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    StatusChip("${items.sumOf { it.pcs }} pcs total")
                }
                Spacer(Modifier.height(8.dp))
                items.toList().forEach { i ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Soft, RoundedCornerShape(14.dp)).padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(i.sku, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("${i.brand}  |  ${i.pcs} pcs", fontSize = 11.sp, color = Muted)
                        }
                        TextButton({ items.remove(i) }) { Text("Remove", color = Red, fontSize = 12.sp) }
                    }
                }
            }
        }
        item {
            PillButton(if (busy) "Submitting..." else "Submit Stock", {
                if (loc.dist.isEmpty() || items.isEmpty()) { msg = "Select distributor and add at least one item"; return@PillButton }
                busy = true; msg = ""
                scope.launch {
                    try {
                        api("submit_stock", s.token) {
                            put("region", loc.region); put("zone", loc.zone); put("base", loc.base); put("distributor", loc.dist)
                            put("items", JSONArray(items.map { JSONObject().put("brand", it.brand).put("sku", it.sku).put("pcs", it.pcs) }))
                        }
                        loc.reset(); items.clear(); onDone()
                    } catch (e: SessionExpired) { onLogout() } catch (e: Exception) { msg = e.message ?: "Failed" }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), enabled = !busy)
            if (msg.isNotEmpty()) { Spacer(Modifier.height(10.dp)); ErrorBox(msg) }
        }
    }
}

@Composable
fun SalesScreen(s: Session, m: Master, onLogout: () -> Unit, onDone: () -> Unit) {
    val loc = remember { Loc() }
    var amount by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTitle("Submit Sales", "Record secondary sales in BDT.")
        AppCard {
            Text("Distributor", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            LocationPicker(m, loc)
        }
        AppCard {
            Text("Secondary Sales", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            AppField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, "Amount (BDT)", keyboard = KeyboardType.Decimal)
        }
        PillButton(if (busy) "Submitting..." else "Submit Sales", {
            val a = amount.toDoubleOrNull() ?: 0.0
            if (loc.dist.isEmpty() || a <= 0.0) { msg = "Select distributor and enter sales amount"; return@PillButton }
            busy = true; msg = ""
            scope.launch {
                try {
                    api("submit_sales", s.token) {
                        put("region", loc.region); put("zone", loc.zone); put("base", loc.base); put("distributor", loc.dist); put("amount", a)
                    }
                    loc.reset(); amount = ""; onDone()
                } catch (e: SessionExpired) { onLogout() } catch (e: Exception) { msg = e.message ?: "Failed" }
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = !busy)
        if (msg.isNotEmpty()) ErrorBox(msg)
    }
}
