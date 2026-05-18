package com.gerard.momofin

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Push/pull MES POINTS vers/depuis le backend Railway. */
object PointsClient {

    fun push(baseUrl: String, token: String, p: DailyPoints): Boolean {
        return try {
            val body = JSONObject()
                .put("day_key", p.dayKey)
                .put("om", p.om).put("momo", p.momo).put("moov", p.moov)
                .put("wave", p.wave).put("djamo", p.djamo).put("cfa", p.cfa)
                .put("entree", p.entree).put("sortie", p.sortie)
                .put("note", p.note).toString()
            val (code, _) = httpPost("$baseUrl/api/points", token, body)
            code in 200..299
        } catch (_: Exception) { false }
    }

    fun pullAll(baseUrl: String, token: String): List<DailyPoints> {
        return try {
            val (code, resp) = httpGet("$baseUrl/api/points", token)
            if (code !in 200..299) return emptyList()
            val arr = JSONArray(resp)
            val list = mutableListOf<DailyPoints>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(DailyPoints(
                    dayKey = o.optLong("day_key"),
                    om = o.optDouble("om", 0.0),
                    momo = o.optDouble("momo", 0.0),
                    moov = o.optDouble("moov", 0.0),
                    wave = o.optDouble("wave", 0.0),
                    djamo = o.optDouble("djamo", 0.0),
                    cfa = o.optDouble("cfa", 0.0),
                    entree = o.optDouble("entree", 0.0),
                    sortie = o.optDouble("sortie", 0.0),
                    note = o.optString("note", "")
                ))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    private fun httpGet(url: String, token: String): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally { c.disconnect() }
    }

    private fun httpPost(url: String, token: String, body: String): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 30_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val resp = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to resp
        } finally { c.disconnect() }
    }
}
