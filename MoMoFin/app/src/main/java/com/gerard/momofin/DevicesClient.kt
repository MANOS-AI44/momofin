package com.gerard.momofin

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RemoteDevice(
    val token: String,
    val label: String,
    val code: String,
    val createdAt: String
)

object DevicesClient {

    fun listAll(baseUrl: String, token: String): List<RemoteDevice> {
        return try {
            val (code, resp) = httpGet("$baseUrl/api/devices", token)
            if (code !in 200..299) return emptyList()
            val arr = JSONArray(resp)
            val list = mutableListOf<RemoteDevice>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(RemoteDevice(
                    token = o.optString("token"),
                    label = o.optString("label"),
                    code = o.optString("code", ""),
                    createdAt = o.optString("created_at")
                ))
            }
            list
        } catch (_: Exception) { emptyList() }
    }

    fun create(baseUrl: String, token: String, label: String): RemoteDevice? {
        return try {
            val body = JSONObject().put("label", label).toString()
            val (code, resp) = httpPost("$baseUrl/api/devices", token, body)
            if (code !in 200..299) return null
            val o = JSONObject(resp)
            if (!o.optBoolean("ok", false)) return null
            RemoteDevice(
                token = o.optString("token"),
                label = o.optString("label"),
                code = o.optString("code"),
                createdAt = ""
            )
        } catch (_: Exception) { null }
    }

    fun delete(baseUrl: String, token: String, deviceToken: String): Boolean {
        return try {
            val (code, _) = httpDelete("$baseUrl/api/devices/$deviceToken", token)
            code in 200..299
        } catch (_: Exception) { false }
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
            requestMethod = "POST"; connectTimeout = 15_000; readTimeout = 30_000; doOutput = true
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

    private fun httpDelete(url: String, token: String): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"; connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally { c.disconnect() }
    }
}
