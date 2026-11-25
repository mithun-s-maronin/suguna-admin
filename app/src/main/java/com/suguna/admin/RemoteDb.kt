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
        val androidId: String = "",
        val battery: Int,
        val lastSeen: Long,
        val currentApp: String = "",
        val lockState: String = "none",
        val pendingCommand: String = "",
        val lastInstall: String = "",
    ) {
        /** Online if it checked in within the last 2 minutes. */
        val online: Boolean get() = System.currentTimeMillis() - lastSeen < 120_000L
        val locked: Boolean get() = lockState == "locked"
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
            if (id == ADMIN_NODE) continue   // the panel's single-device activation claim, not a tablet
            val o = root.optJSONObject(id) ?: continue
            out.add(
                Device(
                    id = id,
                    name = o.optString("name", id),
                    enrollment = o.optString("enrollment", ""),
                    model = o.optString("model", ""),
                    appVersion = o.optString("appVersion", ""),
                    androidId = o.optString("androidId", ""),
                    battery = o.optInt("battery", -1),
                    lastSeen = o.optLong("lastSeen", 0L),
                    currentApp = o.optString("currentApp", ""),
                    lockState = o.optString("lockState", "none"),
                    pendingCommand = o.optJSONObject("command")?.optString("action", "") ?: "",
                    lastInstall = o.optString("lastInstall", ""),
                )
            )
        }
        return Fleet.Ok(dedupe(out))
    }

    /**
     * One physical tablet can hold several records: each fresh anonymous sign-in (after a reinstall
     * or app-data clear) gets a new id. They all report the same stable androidId, so collapse them
     * into the latest check-in per device. Records with no androidId (e.g. a diagnostic probe) are
     * kept as-is so they stay visible and can be removed by hand.
     */
    private fun dedupe(list: List<Device>): List<Device> {
        val byAndroid = LinkedHashMap<String, Device>()
        val loose = ArrayList<Device>()
        for (d in list) {
            if (d.androidId.isBlank()) { loose.add(d); continue }
            val keep = byAndroid[d.androidId]
            if (keep == null || d.lastSeen > keep.lastSeen) byAndroid[d.androidId] = d
        }
        val merged = ArrayList<Device>(byAndroid.values)
        merged.addAll(loose)
        merged.sortWith(compareByDescending<Device> { it.online }.thenBy { it.name.lowercase() })
        return merged
    }

    /** Queue a command for one tablet. It runs the next time the tablet is online. */
    fun sendCommand(deviceId: String, action: String): Boolean {
        val tok = token() ?: return false
        val payload = JSONObject().put("action", action).put("ts", System.currentTimeMillis()).toString()
        return httpPut("$base/devices/$deviceId/command.json?auth=$tok", payload)
    }

    /** Queue a silent app install/update from a download URL. */
    fun sendInstall(deviceId: String, url: String, label: String): Boolean {
        val tok = token() ?: return false
        val payload = JSONObject()
            .put("action", "install_apk")
            .put("url", url)
            .put("label", label)
            .put("ts", System.currentTimeMillis())
            .toString()
        return httpPut("$base/devices/$deviceId/command.json?auth=$tok", payload)
    }

    /** Delete a device record from the panel (stale duplicate, old agent record, test probe). */
    fun removeDevice(deviceId: String): Boolean {
        val tok = token() ?: return false
        return httpDelete("$base/devices/$deviceId.json?auth=$tok")
    }

    // ── Single-device activation lock ────────────────────────────────────────
    // The panel binds to the first device that logs in. The claim lives at /devices/__admin__,
    // writable only by the admin email (the DB rules already allow that), so no rules change is
    // needed and ordinary tablets can't touch it.

    sealed class Binding {
        object Unclaimed : Binding()                       // no device has activated yet
        data class ClaimedBy(val androidId: String, val label: String) : Binding()
        object Error : Binding()                           // couldn't reach the DB / not logged in
    }

    fun readBinding(): Binding {
        val tok = token() ?: return Binding.Error
        val (code, body) = httpGet("$base/devices/$ADMIN_NODE.json?auth=$tok")
        if (code !in 200..299) return Binding.Error
        if (body == null || body.isBlank() || body == "null") return Binding.Unclaimed
        return try {
            val o = JSONObject(body)
            val aid = o.optString("boundAndroidId", "")
            if (aid.isBlank()) Binding.Unclaimed
            else Binding.ClaimedBy(aid, o.optString("label", "another device"))
        } catch (e: Exception) { Binding.Error }
    }

    /** Claim the panel for this device (only used when no device has claimed it yet). */
    fun writeBinding(androidId: String, label: String): Boolean {
        val tok = token() ?: return false
        val payload = JSONObject()
            .put("boundAndroidId", androidId)
            .put("label", label)
            .put("boundAt", System.currentTimeMillis())
            .toString()
        return httpPut("$base/devices/$ADMIN_NODE.json?auth=$tok", payload)
    }

    /** Release the single-device lock so the panel can be activated on a different device. */
    fun clearBinding(): Boolean {
        val tok = token() ?: return false
        return httpDelete("$base/devices/$ADMIN_NODE.json?auth=$tok")
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

    private fun httpDelete(url: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "DELETE"; c.connectTimeout = 15000; c.readTimeout = 20000
        val ok = c.responseCode in 200..299
        if (!ok) Log.w(TAG, "DELETE ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()}")
        c.disconnect(); ok
    } catch (e: Exception) { Log.w(TAG, "DELETE failed: ${e.message}"); false }

    private companion object {
        const val TAG = "RemoteDb"
        const val ADMIN_NODE = "__admin__"
    }
}
