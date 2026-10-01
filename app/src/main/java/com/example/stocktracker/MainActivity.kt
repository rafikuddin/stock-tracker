package com.example.stocktracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class Session(val token: String, val name: String, val email: String)
data class Item(val brand: String, val sku: String, val pcs: Int)
data class DistStat(val distributor: String, val submissions: Int, val totalPcs: Long, val last: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { Root() } } }
    }
}

@Composable
fun Root() {
    var session by remember { mutableStateOf<Session?>(null) }
    val s = session
    if (s == null) LoginScreen { session = it } else HomeScreen(s) { session = null }
}

@Composable
fun LoginScreen(onLogin: (Session) -> Unit) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("Stock Tracker", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation())
        Spacer(Modifier.height(12.dp))
        Button({
            busy = true; msg = ""
            scope.launch {
                try {
                    val r = api("login") { put("email", email.trim()); put("password", pass) }
                    onLogin(Session(r.getString("token"), r.optString("name"), email.trim()))
                } catch (e: Exception) { msg = e.message ?: "Error" }
                busy = false
            }
        }, Modifier.fillMaxWidth(), enabled = !busy && email.isNotBlank() && pass.isNotEmpty()) { Text(if (busy) "Please wait..." else "Login") }
        if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun HomeScreen(s: Session, onLogout: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    var master by remember { mutableStateOf<Master?>(null) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { master = parseMaster(api("master", s.token)) }
        catch (e: SessionExpired) { onLogout() } catch (e: Exception) { err = e.message ?: "Failed to load lists" }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Hi, ${s.name.ifBlank { s.email }}")
            TextButton(onLogout) { Text("Logout") }
        }
        TabRow(tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Dashboard") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Submit Data") })
        }
        if (err.isNotEmpty()) Text(err, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        if (tab == 0) Dashboard(s, onLogout)
        else master?.let { SubmitScreen(s, it, onLogout) { tab = 0 } }
            ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

@Composable
fun Dashboard(s: Session, onLogout: () -> Unit) {
    var stats by remember { mutableStateOf<List<DistStat>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        loading = true; err = ""
        try {
            val a: JSONArray = api("dashboard", s.token).getJSONArray("data")
            stats = (0 until a.length()).map { a.getJSONObject(it) }.map {
                DistStat(it.getString("distributor"), it.getInt("submissions"), it.getLong("totalPcs"), it.optString("last"))
            }
        } catch (e: SessionExpired) { onLogout() } catch (e: Exception) { err = e.message ?: "Error" }
        loading = false
    }
    if (loading) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Submissions by Distributor", style = MaterialTheme.typography.titleMedium)
                TextButton({ reload++ }) { Text("Refresh") }
            }
        }
        if (err.isNotEmpty()) item { Text(err, color = MaterialTheme.colorScheme.error) }
        else if (stats.isEmpty()) item { Text("No data yet") }
        items(stats) { d ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text(d.distributor, style = MaterialTheme.typography.titleSmall)
                Text("Submissions: ${d.submissions}   |   Total pcs: ${d.totalPcs}")
                Text("Last: ${d.last}", style = MaterialTheme.typography.bodySmall)
            } }
        }
    }
}

@Composable
fun SubmitScreen(s: Session, m: Master, onLogout: () -> Unit, onDone: () -> Unit) {
    var region by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf("") }
    var dist by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var sku by remember { mutableStateOf("") }
    var pcs by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<Item>() }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Dropdown("Region", m.regions.keys.toList(), region) { region = it; zone = ""; dist = "" }
            Dropdown("Zone", m.regions[region]?.keys?.toList() ?: emptyList(), zone, region.isNotEmpty()) { zone = it; dist = "" }
            Dropdown("Distributor", m.regions[region]?.get(zone) ?: emptyList(), dist, zone.isNotEmpty()) { dist = it }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Add Stock", style = MaterialTheme.typography.titleMedium)
            Dropdown("Brand", m.brands.keys.toList(), brand) { brand = it; sku = "" }
            Dropdown("SKU", m.brands[brand] ?: emptyList(), sku, brand.isNotEmpty()) { sku = it }
            OutlinedTextField(pcs, { pcs = it.filter(Char::isDigit) }, label = { Text("Pcs") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button({
                val n = pcs.toIntOrNull()
                if (brand.isNotEmpty() && sku.isNotEmpty() && n != null && n > 0) { items.add(Item(brand, sku, n)); sku = ""; pcs = "" }
            }, Modifier.fillMaxWidth()) { Text("+ Add Item") }
        }
        items(items.toList()) { i ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${i.brand} / ${i.sku}: ${i.pcs} pcs", Modifier.weight(1f))
                TextButton({ items.remove(i) }) { Text("Remove") }
            }
        }
        item {
            Button({
                if (dist.isEmpty() || items.isEmpty()) { msg = "Select distributor and add at least one item"; return@Button }
                busy = true; msg = ""
                scope.launch {
                    try {
                        api("submit", s.token) {
                            put("region", region); put("zone", zone); put("distributor", dist)
                            put("items", JSONArray(items.map { JSONObject().put("brand", it.brand).put("sku", it.sku).put("pcs", it.pcs) }))
                        }
                        onDone()
                    } catch (e: SessionExpired) { onLogout() } catch (e: Exception) { msg = e.message ?: "Failed" }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), enabled = !busy) { Text(if (busy) "Submitting..." else "Submit") }
            if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Dropdown(label: String, options: List<String>, selected: String, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var exp by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(exp, { if (enabled) exp = !exp }) {
        OutlinedTextField(selected, {}, readOnly = true, enabled = enabled, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(exp) },
            modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(exp, { exp = false }) {
            options.forEach { o -> DropdownMenuItem({ Text(o) }, { onSelect(o); exp = false }) }
        }
    }
}
