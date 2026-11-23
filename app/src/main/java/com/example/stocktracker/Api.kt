package com.example.stocktracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

const val API_URL = "https://script.google.com/macros/s/AKfycbzWCwhJZMHIj9iQqvvh-uI-8Mr3a53yAtbPKCJy4dPLnCtJ0XYILsXtfc7oPfp9CSLd/exec"

class SessionExpired : Exception("Session expired, please login again")

suspend fun api(action: String, token: String = "", build: JSONObject.() -> Unit = {}): JSONObject =
    withContext(Dispatchers.IO) {
        val body = JSONObject().put("action", action).put("token", token).apply(build)
        val c = URL(API_URL).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 20000; c.readTimeout = 40000
        c.setRequestProperty("Content-Type", "text/plain")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
        val raw = stream.bufferedReader().readText()
        val res = try { JSONObject(raw) } catch (e: Exception) {
            val txt = raw.replace(Regex("<style[\\s\\S]*?</style>|<script[\\s\\S]*?</script>|<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ").trim().take(250)
            throw Exception("Server returned a web page instead of data (HTTP ${c.responseCode}): $txt")
        }
        if (!res.optBoolean("ok")) {
            val err = res.optString("error", "Request failed")
            if (err == "SESSION_EXPIRED") throw SessionExpired()
            throw Exception(err)
        }
        res
    }

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONObject.keyList(): List<String> = keys().asSequence().toList()

// regions: Region > Zone > Base > [Distributor]
// distBu:  "region|zone|base|distributor" -> BU      buBrands: BU > Brand > [SKU]
data class Master(
    val regions: Map<String, Map<String, Map<String, List<String>>>>,
    val brands: Map<String, List<String>>,
    val distBu: Map<String, String> = emptyMap(),
    val buBrands: Map<String, Map<String, List<String>>> = emptyMap()
)

fun parseMaster(j: JSONObject): Master {
    val r = j.getJSONObject("regions"); val b = j.getJSONObject("brands")
    val db = j.optJSONObject("distBu"); val bb = j.optJSONObject("buBrands")
    val distBu: Map<String, String> = if (db == null) emptyMap() else db.keyList().associateWith { db.getString(it) }
    val buBrands: Map<String, Map<String, List<String>>> = if (bb == null) emptyMap() else
        bb.keyList().associateWith { bu ->
            val o = bb.getJSONObject(bu)
            o.keyList().associateWith { o.getJSONArray(it).strings() }
        }
    return Master(
        r.keyList().associateWith { reg ->
            val z = r.getJSONObject(reg)
            z.keyList().associateWith { zn ->
                val bs = z.getJSONObject(zn)
                bs.keyList().associateWith { bn -> bs.getJSONArray(bn).strings() }
            }
        },
        b.keyList().associateWith { b.getJSONArray(it).strings() },
        distBu, buBrands
    )
}
