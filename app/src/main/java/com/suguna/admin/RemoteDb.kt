package com.suguna.admin

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to a Firebase Realtime Database over its REST API (no Firebase SDK needed).
 *
 * Data model written by each managed tablet (the SugunaHome fork) under /devices/<id>:
 *   { name, enrollment, model, appVersion, battery, lastSeen (epoch ms) }
 * A command is delivered by writing /devices/<id>/command = { action, ts }.
 * The tablet executes it and deletes the command node.
 */
class RemoteDb(private val baseUrl: String, private val secret: String) {

    data class Device(
        val id: String,
        val name: String,
        val enrollment: String,
        val model: String,
        val appVersion: String,
        val battery: Int,
        val lastSeen: Long,
    ) {
        /** Online if it checked in within the last 2 minutes. */
        val online: Boolean get() = System.currentTimeMillis() - lastSeen < 120_000L
    }

    /** GET /devices.json — returns all devices that have ever checked in. */
    fun listDevices(): List<Device> {
        val body = httpGet("$baseUrl/devices.json?auth=$secret") ?: return emptyList()
        if (body.isBlank() || body == "null") return emptyList()
        val root = JSONObject(body)
        val out = ArrayList<Device>()
        for (id in root.keys()) {
            val o = root.optJSONObject(id) ?: continue
            out.add(
                Device(
                    id = id,
                    name = o.optString("name", id),
                    enrollment = o.optString("enrollment", ""),
                    model = o.optString("model", ""),
                    appVersion = o.optString("appVersion", ""),
                    battery = o.optInt("battery", -1),
                    lastSeen = o.optLong("lastSeen", 0L),
                )
            )
        }
        out.sortWith(compareByDescending<Device> { it.online }.thenBy { it.name.lowercase() })
        return out
    }

    /** PUT /devices/<id>/command.json — queue a command for the tablet to execute. */
    fun sendCommand(deviceId: String, action: String): Boolean {
        val payload = JSONObject()
            .put("action", action)
            .put("ts", System.currentTimeMillis())
            .toString()
        return httpPut("$baseUrl/devices/$deviceId/command.json?auth=$secret", payload)
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────

    private fun httpGet(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15000
        c.readTimeout = 20000
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        if (code in 200..299) text else { Log.w(TAG, "GET $code: $text"); null }
    } catch (e: Exception) {
        Log.w(TAG, "GET failed: ${e.message}"); null
    }

    private fun httpPut(url: String, body: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val ok = c.responseCode in 200..299
        if (!ok) Log.w(TAG, "PUT ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()}")
        c.disconnect()
        ok
    } catch (e: Exception) {
        Log.w(TAG, "PUT failed: ${e.message}"); false
    }

    private companion object { const val TAG = "RemoteDb" }
}
