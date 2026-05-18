package com.gerard.momofin

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Client HTTP minimal pour synchroniser les transactions avec MoMo Fin Web (Railway).
 * Pas de dépendance externe (utilise HttpURLConnection + org.json).
 */
object RailwayClient {

    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    data class Result(val ok: Boolean, val message: String, val inserted: Int = 0)

    fun ping(baseUrl: String, token: String): Result {
        return try {
            val (code, body) = httpGet("$baseUrl/api/whoami", token)
            if (code == 200) {
                val j = JSONObject(body)
                Result(true, "Connecté à : ${j.optString("label", "(sans nom)")}")
            } else {
                Result(false, "HTTP $code — $body")
            }
        } catch (e: Exception) {
            Result(false, "Erreur réseau : ${e.message}")
        }
    }

    fun syncTransactions(baseUrl: String, token: String, txs: List<Transaction>): Result {
        return try {
            val arr = JSONArray()
            for (t in txs) {
                val o = JSONObject()
                    .put("sender", t.rawSender)
                    .put("body", t.rawBody)
                    .put("smsTimestamp", t.timestamp)
                    .put("operator", t.operator)
                    .put("type", t.type.name)
                    .put("amount", t.amount)
                    .put("currency", t.currency)
                    .put("reference", t.reference)
                    .put("phone_number", t.phoneNumber)
                    .put("ts", ISO.format(Date(t.timestamp)))
                arr.put(o)
            }
            val body = JSONObject().put("transactions", arr).toString()
            val (code, resp) = httpPost("$baseUrl/api/transactions/sync", token, body)
            if (code in 200..299) {
                val j = JSONObject(resp)
                val inserted = j.optInt("inserted", 0)
                Result(true, "Synchronisé : $inserted nouvelles transactions sur ${txs.size}.", inserted)
            } else {
                Result(false, "HTTP $code — $resp")
            }
        } catch (e: Exception) {
            Result(false, "Erreur réseau : ${e.message}")
        }
    }

    private fun httpGet(url: String, token: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to body
        } finally {
            conn.disconnect()
        }
    }

    
    fun pullTransactions(baseUrl: String, token: String): List<SmsSource.Raw> {
        return try {
            val (code, resp) = httpGet("$baseUrl/api/transactions", token)
            if (code !in 200..299) return emptyList()
            val arr = org.json.JSONArray(resp)
            val list = mutableListOf<SmsSource.Raw>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // Reconstruit un Raw fictif à partir des données serveur
                val body = "${o.optString("type")} ${o.optDouble("amount", 0.0)} ${o.optString("currency")} " +
                           "Ref: ${o.optString("reference")} " +
                           "Num: ${o.optString("phone_number")}"
                val ts = try {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .parse(o.optString("ts").substring(0, 19))?.time ?: 0L
                } catch (_: Exception) { 0L }
                list.add(SmsSource.Raw(
                    id = o.optLong("id"),
                    sender = o.optString("raw_sender", "Serveur"),
                    body = body,
                    timestamp = ts,
                    operator = o.optString("operator", "Autre")
                ))
            }
            list
        } catch (_: Exception) { emptyList() }
    }


    /**
     * Variante qui renvoie directement des Transaction deja parsees par le backend.
     * A utiliser sur les sous-comptes (mode lecture seule) : evite de retraverser
     * le parser strict local qui rejetterait les bodies fictifs reconstruits.
     */
    fun pullTransactionsAsTx(baseUrl: String, token: String): List<Transaction> {
        return try {
            val (code, resp) = httpGet("$baseUrl/api/transactions", token)
            if (code !in 200..299) return emptyList()
            val arr = org.json.JSONArray(resp)
            val list = mutableListOf<Transaction>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ts = try {
                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .parse(o.optString("ts").substring(0, 19))?.time ?: 0L
                } catch (_: Exception) { 0L }
                val type = when (o.optString("type").uppercase()) {
                    "RECU" -> TxType.RECU
                    "SORTIE" -> TxType.SORTIE
                    else -> TxType.INCONNU
                }
                if (type == TxType.INCONNU) continue
                val amount = o.optDouble("amount", 0.0)
                if (amount <= 0.0) continue
                list.add(Transaction(
                    rawId = o.optLong("id"),
                    operator = o.optString("operator", "Autre"),
                    type = type,
                    amount = amount,
                    currency = o.optString("currency").ifBlank { "FCFA" },
                    reference = o.optString("reference", ""),
                    phoneNumber = TransactionParser.normalizePhone(o.optString("phone_number", "")),
                    timestamp = ts,
                    rawSender = o.optString("raw_sender", "Serveur"),
                    rawBody = ""
                ))
            }
            list
        } catch (_: Exception) { emptyList() }
    }


    private fun httpPost(url: String, token: String, body: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to resp
        } finally {
            conn.disconnect()
        }
    }
}
