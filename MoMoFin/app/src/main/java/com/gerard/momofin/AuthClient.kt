package com.gerard.momofin

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Inscription et connexion via le backend Railway directement depuis l'APK.
 */
object AuthClient {

    data class Result(val ok: Boolean, val token: String? = null, val email: String? = null, val message: String = "")

    private const val DEFAULT_URL = "https://momofin-production.up.railway.app"

    fun signup(baseUrl: String, email: String, password: String, name: String): Result {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .put("name", name)
            .toString()
        return post("$baseUrl/api/auth/inscription", body)
    }

    fun login(baseUrl: String, email: String, password: String): Result {
        val body = JSONObject()
            .put("email", email)
            .put("password", password)
            .toString()
        return post("$baseUrl/api/auth/connexion", body)
    }

    fun defaultUrl(): String = DEFAULT_URL

    private fun post(url: String, jsonBody: String): Result {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
                val j = JSONObject(if (resp.isBlank()) "{}" else resp)
                if (code in 200..299 && j.optBoolean("ok", false)) {
                    Result(
                        ok = true,
                        token = j.optString("token"),
                        email = j.optString("email"),
                        message = "Bienvenue ${j.optString("name", j.optString("email", ""))}"
                    )
                } else {
                    Result(false, message = j.optString("error", "HTTP $code"))
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Result(false, message = "Erreur réseau : ${e.message}")
        }
    }
}
