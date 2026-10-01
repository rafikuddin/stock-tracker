package com.example.stocktracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// >>> Paste your Apps Script Web App URL here (ends with /exec) <<<
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
        val res = JSONObject(stream.bufferedReader().readText())
        if (!res.optBoolean("ok")) {
            val err = res.optString("error", "Request failed")
            if (err == "SESSION_EXPIRED") throw SessionExpired()
            throw Exception(err)
        }
        res
    }

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONObject.keyList(): List<String> = keys().asSequence().toList()

data class Master(val regions: Map<String, Map<String, List<String>>>, val brands: Map<String, List<String>>)

fun parseMaster(j: JSONObject): Master {
    val r = j.getJSONObject("regions"); val b = j.getJSONObject("brands")
    return Master(
        r.keyList().associateWith { reg -> r.getJSONObject(reg).let { z -> z.keyList().associateWith { z.getJSONArray(it).strings() } } },
        b.keyList().associateWith { b.getJSONArray(it).strings() }
    )
}
