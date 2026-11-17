package com.suguna.admin

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var subtitle: TextView
    private lateinit var empty: TextView
    private lateinit var adapter: DeviceAdapter

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var polling = false
    private var unlocked = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshOnce()
            if (polling) ui.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        subtitle = findViewById(R.id.subtitle)
        empty = findViewById(R.id.empty)
        adapter = DeviceAdapter { device -> showDeviceActions(device) }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        when {
            !prefs.isConfigured -> firstRunSetup()
            prefs.boundDeviceId != androidId() -> lockedToAnotherDevice()
            else -> promptPassword()
        }
    }

    @SuppressLint("HardwareIds")
    private fun androidId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    // ── First-run setup: password + Firebase config, bound to this device ──
    private fun firstRunSetup() {
        subtitle.text = "First-time setup"
        val pw = edit("Create admin password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val pw2 = edit("Confirm password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val url = edit("Firebase Database URL", InputType.TYPE_TEXT_VARIATION_URI)
        val secret = edit("Firebase database secret", InputType.TYPE_CLASS_TEXT)
        val box = column(pw, pw2, url, secret)
        AlertDialog.Builder(this)
            .setTitle("Set up Suguna Admin")
            .setMessage("This locks the app to THIS device. Keep the password safe.")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                val p = pw.text.toString()
                when {
                    p.length < 4 -> { toast("Password too short"); firstRunSetup() }
                    p != pw2.text.toString() -> { toast("Passwords don't match"); firstRunSetup() }
                    url.text.isBlank() || secret.text.isBlank() -> { toast("Enter Firebase URL and secret"); firstRunSetup() }
                    else -> {
                        prefs.setPassword(p)
                        prefs.dbUrl = url.text.toString()
                        prefs.dbSecret = secret.text.toString()
                        prefs.boundDeviceId = androidId()
                        unlock()
                    }
                }
            }
            .setNegativeButton("Quit") { _, _ -> finish() }
            .show()
    }

    private fun lockedToAnotherDevice() {
        subtitle.text = "Locked"
        AlertDialog.Builder(this)
            .setTitle("Locked to another device")
            .setMessage("This admin app was set up on a different device and can't be used here.")
            .setCancelable(false)
            .setPositiveButton("Close") { _, _ -> finish() }
            .show()
    }

    private fun promptPassword() {
        subtitle.text = "Locked"
        val pw = edit("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        AlertDialog.Builder(this)
            .setTitle("Enter admin password")
            .setView(column(pw))
            .setCancelable(false)
            .setPositiveButton("Unlock") { _, _ ->
                if (prefs.checkPassword(pw.text.toString())) unlock()
                else { toast("Wrong password"); promptPassword() }
            }
            .setNegativeButton("Quit") { _, _ -> finish() }
            .show()
    }

    private fun unlock() {
        unlocked = true
        startPolling()
    }

    // ── Polling ───────────────────────────────────────────────────────────
    private fun startPolling() {
        if (polling) return
        polling = true
        ui.post(pollRunnable)
    }

    private fun refreshOnce() {
        val db = RemoteDb(prefs.dbUrl, prefs.dbSecret)
        io.execute {
            val devices = try { db.listDevices() } catch (e: Exception) { null }
            ui.post {
                if (devices == null) {
                    subtitle.text = "Can't reach the server — check connection/config"
                    return@post
                }
                adapter.submit(devices)
                val online = devices.count { it.online }
                subtitle.text = "${devices.size} device(s) • $online online"
                empty.visibility = if (devices.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    // ── Per-device actions ─────────────────────────────────────────────────
    private fun showDeviceActions(d: RemoteDb.Device) {
        if (!d.online) {
            AlertDialog.Builder(this)
                .setTitle(d.name)
                .setMessage("This device is OFFLINE. Commands run only when it is online.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(d.name)
            .setItems(arrayOf("Lock now", "Factory reset")) { _, which ->
                when (which) {
                    0 -> confirmAndSend(d, "lock", "Lock ${d.name}?", "The tablet will lock immediately.")
                    1 -> confirmFactoryReset(d)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmFactoryReset(d: RemoteDb.Device) {
        AlertDialog.Builder(this)
            .setTitle("Factory reset ${d.name}?")
            .setMessage("This ERASES the tablet completely and cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Are you absolutely sure?")
                    .setMessage("Everything on ${d.name} will be permanently wiped.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Reset now") { _, _ -> send(d, "factory_reset") }
                    .show()
            }
            .show()
    }

    private fun confirmAndSend(d: RemoteDb.Device, action: String, title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ -> send(d, action) }
            .show()
    }

    private fun send(d: RemoteDb.Device, action: String) {
        val db = RemoteDb(prefs.dbUrl, prefs.dbSecret)
        io.execute {
            val ok = db.sendCommand(d.id, action)
            ui.post { toast(if (ok) "Sent \"$action\" to ${d.name}" else "Failed to send — try again") }
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────
    private fun edit(hint: String, type: Int): EditText = EditText(this).apply {
        this.hint = hint
        inputType = type
    }

    private fun column(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        gravity = Gravity.CENTER_HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        if (unlocked) startPolling()
    }

    override fun onPause() {
        super.onPause()
        polling = false
        ui.removeCallbacks(pollRunnable)
    }

    companion object { private const val POLL_MS = 10_000L }
}
