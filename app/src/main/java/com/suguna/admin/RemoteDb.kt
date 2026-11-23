package com.suguna.admin

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the Firebase Realtime Database over REST, authenticated with the admin's ID token
 * (no Firebase SDK, no secret). Each tablet writes /devices/<uid>; a command is queued by
 * writing /devices/<uid>/command = { action, ts } and the tablet clears it once run.
 */
class RemoteDb(private val context: Context) {

    private val base = Config.FIREBASE_DB_URL.trimEnd('/')
    private fun token(): String? = FbAuth.idToken(context)

    /** Result of a fleet read, so the UI can tell "offline" from "permission denied". */
    sealed class Fleet {
        data class Ok(val devices: List<Device>) : Fleet()
        object AuthError : Fleet()      // 401/403 — admin email likely doesn't match the rules
        object Unreachable : Fleet()    // network / not logged in
    }

    data class Device(
        val id: String,
        val name: String,
        val enrollment: String,
        val model: String,
        val appVersion: String,
        val battery: Int,
        val lastSeen: Long,
        val lat: Double? = null,
        val lng: Double? = null,
        val locAccuracy: Int = -1,
        val locTime: Long = 0L,
        val currentApp: String = "",
        val lockState: String = "none",
        val pendingCommand: String = "",
    ) {
        /** Online if it checked in within the last 2 minutes. */
        val online: Boolean get() = System.currentTimeMillis() - lastSeen < 120_000L
        val locked: Boolean get() = lockState == "locked"
        val hasLocation: Boolean get() = lat != null && lng != null
        val hasPending: Boolean get() = pendingCommand.isNotBlank()
        /** "App Label" out of the "Label|package" the tablet reports (falls back to the package). */
        val currentAppLabel: String get() = currentApp.substringBefore('|', currentApp)
    }

    fun listDevices(): Fleet {
        val tok = token() ?: return Fleet.Unreachable
        val (code, body) = httpGet("$base/devices.json?auth=$tok")
        if (code == 401 || code == 403) return Fleet.AuthError
        if (body == null) return Fleet.Unreachable
        if (body.isBlank() || body == "null") return Fleet.Ok(emptyList())
        val root = try { JSONObject(body) } catch (e: Exception) { return Fleet.Unreachable }
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
                    lat = if (o.has("lat")) o.optDouble("lat") else null,
                    lng = if (o.has("lng")) o.optDouble("lng") else null,
                    locAccuracy = o.optInt("locAccuracy", -1),
                    locTime = o.optLong("locTime", 0L),
                    currentApp = o.optString("currentApp", ""),
                    lockState = o.optString("lockState", "none"),
                    pendingCommand = o.optJSONObject("command")?.optString("action", "") ?: "",
                )
            )
        }
        out.sortWith(compareByDescending<Device> { it.online }.thenBy { it.name.lowercase() })
        return Fleet.Ok(out)
    }

    /** Queue a command for one tablet. It runs the next time the tablet is online. */
    fun sendCommand(deviceId: String, action: String): Boolean {
        val tok = token() ?: return false
        val payload = JSONObject().put("action", action).put("ts", System.currentTimeMillis()).toString()
        return httpPut("$base/devices/$deviceId/command.json?auth=$tok", payload)
    }

    // ── HTTP ─────────────────────────────────────────────────────────────
    private fun httpGet(url: String): Pair<Int, String?> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = 15000; c.readTimeout = 20000
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect()
        if (code !in 200..299) Log.w(TAG, "GET $code: $text")
        code to (if (code in 200..299) text else null)
    } catch (e: Exception) { Log.w(TAG, "GET failed: ${e.message}"); -1 to null }

    private fun httpPut(url: String, body: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val ok = c.responseCode in 200..299
        if (!ok) Log.w(TAG, "PUT ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()}")
        c.disconnect(); ok
    } catch (e: Exception) { Log.w(TAG, "PUT failed: ${e.message}"); false }

    private companion object { const val TAG = "RemoteDb" }
}
