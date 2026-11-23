package com.suguna.admin

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
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
    private lateinit var onlinePill: TextView
    private lateinit var empty: TextView
    private lateinit var adapter: DeviceAdapter

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var polling = false
    private var loggedIn = false
    private var lastDevices: List<RemoteDb.Device> = emptyList()

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
        onlinePill = findViewById(R.id.onlinePill)
        empty = findViewById(R.id.empty)
        adapter = DeviceAdapter { device -> showDeviceActions(device) }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        findViewById<TextView>(R.id.menuBtn).setOnClickListener { showMenu(it) }
        findViewById<TextView>(R.id.lockAll).setOnClickListener { bulk("lock", "Lock ALL devices?") }

        if (FbAuth.isLoggedIn(this)) onLoggedIn() else promptLogin()
    }

    // ── Login ───────────────────────────────────────────────────────────────
    private fun promptLogin(prefill: String = prefs.adminEmail) {
        subtitle.text = "Sign in"
        val email = edit("Admin email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply { setText(prefill) }
        val pass = edit("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        AlertDialog.Builder(this)
            .setTitle("Suguna Admin login")
            .setMessage("Use the admin email in your database rules. First login creates the account.")
            .setView(column(email, pass))
            .setCancelable(false)
            .setPositiveButton("Log in", null) // set below so it doesn't auto-dismiss on error
            .setNegativeButton("Quit") { _, _ -> finish() }
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val e = email.text.toString().trim()
                        val p = pass.text.toString()
                        if (e.isBlank() || p.isBlank()) { toast("Enter email and password"); return@setOnClickListener }
                        subtitle.text = "Signing in…"
                        dismiss()
                        io.execute {
                            val r = FbAuth.login(this@MainActivity, e, p)
                            ui.post {
                                when (r) {
                                    is FbAuth.LoginResult.Ok -> onLoggedIn()
                                    is FbAuth.LoginResult.Created -> { toast("Admin account created"); onLoggedIn() }
                                    is FbAuth.LoginResult.Error -> { toast(r.message); promptLogin(e) }
                                }
                            }
                        }
                    }
                }
            }.show()
    }

    private fun onLoggedIn() {
        loggedIn = true
        startPolling()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Lock all devices")
            menu.add("Unlock all devices")
            menu.add("Refresh now")
            menu.add("Log out")
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Lock all devices" -> bulk("lock", "Lock ALL devices?")
                    "Unlock all devices" -> bulk("unlock", "Unlock ALL devices?")
                    "Refresh now" -> refreshOnce()
                    "Log out" -> logout()
                }
                true
            }
        }.show()
    }

    private fun logout() {
        AlertDialog.Builder(this)
            .setTitle("Log out?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Log out") { _, _ ->
                polling = false
                FbAuth.logout(this)
                loggedIn = false
                adapter.submit(emptyList())
                onlinePill.text = "0 online"
                promptLogin()
            }
            .show()
    }

    // ── Polling ───────────────────────────────────────────────────────────
    private fun startPolling() {
        if (polling) return
        polling = true
        ui.post(pollRunnable)
    }

    private fun refreshOnce() {
        val db = RemoteDb(this)
        io.execute {
            val fleet = try { db.listDevices() } catch (e: Exception) { RemoteDb.Fleet.Unreachable }
            ui.post {
                when (fleet) {
                    is RemoteDb.Fleet.AuthError ->
                        subtitle.text = "Access denied — your admin email must match the database rules"
                    is RemoteDb.Fleet.Unreachable ->
                        subtitle.text = "Can't reach the database — check your connection"
                    is RemoteDb.Fleet.Ok -> {
                        lastDevices = fleet.devices
                        adapter.submit(fleet.devices)
                        val online = fleet.devices.count { it.online }
                        val who = prefs.adminEmail.ifBlank { "admin" }
                        subtitle.text = "${fleet.devices.size} device(s) • signed in as $who"
                        onlinePill.text = "$online online"
                        empty.visibility = if (fleet.devices.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    // ── Per-device actions ─────────────────────────────────────────────────
    private fun showDeviceActions(d: RemoteDb.Device) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (d.locked) {
            labels.add("🔓  Unlock")
            actions.add { confirmAndSend(d, "unlock", "Unlock ${d.name}?",
                "Releases the lock so the tablet can be used again.") }
        } else {
            labels.add("🔒  Lock (only you can unlock)")
            actions.add { confirmAndSend(d, "lock", "Lock ${d.name}?",
                "A full-screen lock stays up until you unlock it here. The student can't get past it.") }
        }
        labels.add("🔑  Reset screen lock")
        actions.add { confirmAndSend(d, "reset_screen_lock", "Reset screen lock on ${d.name}?",
            "Clears the tablet's screen PIN/password so a locked-out student can get back in.") }
        labels.add("♻️  Factory reset")
        actions.add { confirmFactoryReset(d) }
        if (d.hasLocation) {
            labels.add("📍  View location on map")
            actions.add { openMap(d) }
        }
        labels.add("🗑️  Remove from panel")
        actions.add { confirmRemove(d) }

        AlertDialog.Builder(this)
            .setTitle(d.name.ifBlank { d.id } + if (d.locked) "  🔒" else "")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmFactoryReset(d: RemoteDb.Device) {
        AlertDialog.Builder(this)
            .setTitle("Factory reset ${d.name}?")
            .setMessage("This ERASES the tablet and cannot be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Are you sure?")
                    .setMessage("Everything on ${d.name} will be wiped.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Reset") { _, _ -> send(d.id, "factory_reset", d) }
                    .show()
            }
            .show()
    }

    private fun confirmRemove(d: RemoteDb.Device) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${d.name.ifBlank { d.id }} from the panel?")
            .setMessage("Deletes this record from the admin panel only — it does NOT touch the tablet. " +
                "Use it to clear stale duplicates or old test entries. If the tablet is still running " +
                "the app, it'll reappear at its next check-in.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                val db = RemoteDb(this)
                io.execute {
                    val ok = db.removeDevice(d.id)
                    ui.post {
                        toast(if (ok) "Removed ${d.name.ifBlank { d.id }}" else "Failed — try again")
                        if (ok) refreshOnce()
                    }
                }
            }
            .show()
    }

    private fun confirmAndSend(d: RemoteDb.Device, action: String, title: String, msg: String) {
        val queued = if (!d.online) "\n\nThis tablet is offline — it'll run as soon as it comes online." else ""
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg + queued)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ -> send(d.id, action, d) }
            .show()
    }

    private fun send(id: String, action: String, d: RemoteDb.Device) {
        val db = RemoteDb(this)
        io.execute {
            val ok = db.sendCommand(id, action)
            ui.post {
                toast(if (ok) {
                    if (d.online) "Sent to ${d.name}" else "Queued for ${d.name} (offline)"
                } else "Failed — try again")
                if (ok) refreshOnce()
            }
        }
    }

    // ── Bulk ─────────────────────────────────────────────────────────────
    private fun bulk(action: String, title: String) {
        val targets = lastDevices
        if (targets.isEmpty()) { toast("No devices yet"); return }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("${targets.size} device(s) will receive \"$action\". Offline ones run when they reconnect.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send to all") { _, _ ->
                val db = RemoteDb(this)
                io.execute {
                    var ok = 0
                    for (d in targets) if (db.sendCommand(d.id, action)) ok++
                    ui.post { toast("Sent to $ok/${targets.size}"); refreshOnce() }
                }
            }
            .show()
    }

    private fun openMap(d: RemoteDb.Device) {
        val lat = d.lat ?: return
        val lng = d.lng ?: return
        val uri = android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng(${android.net.Uri.encode(d.name)})")
        try {
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            val web = android.net.Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lng")
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, web)) }
            catch (e2: Exception) { toast("No app can open maps") }
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
        views.forEach { addView(it) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        if (loggedIn) startPolling()
    }

    override fun onPause() {
        super.onPause()
        polling = false
        ui.removeCallbacks(pollRunnable)
    }

    companion object { private const val POLL_MS = 10_000L }
}
