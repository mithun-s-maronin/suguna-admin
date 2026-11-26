package com.suguna.admin

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
        checkActivation()
    }

    // ── Single-device activation lock ────────────────────────────────────────
    /** This admin app's stable per-device id (differs from the MDM's — different signing key). */
    private fun thisDeviceId(): String =
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    private fun thisDeviceLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    /** Bind the panel to the first device that logs in; refuse to run on any other. */
    private fun checkActivation() {
        subtitle.text = "Checking activation…"
        val db = RemoteDb(this)
        io.execute {
            val b = db.readBinding()
            ui.post {
                when (b) {
                    is RemoteDb.Binding.Error -> AlertDialog.Builder(this)
                        .setTitle("Couldn't verify activation")
                        .setMessage("Can't reach the database to check which device this panel is locked to. Check your connection and retry.")
                        .setCancelable(false)
                        .setNegativeButton("Log out") { _, _ -> logout() }
                        .setPositiveButton("Retry") { _, _ -> checkActivation() }
                        .show()
                    is RemoteDb.Binding.Unclaimed -> io.execute {
                        val ok = db.writeBinding(thisDeviceId(), thisDeviceLabel())
                        ui.post {
                            if (ok) { toast("Panel activated on this device"); startPolling() }
                            else AlertDialog.Builder(this)
                                .setTitle("Activation failed")
                                .setMessage("Couldn't claim this device. Retry?")
                                .setCancelable(false)
                                .setNegativeButton("Log out") { _, _ -> logout() }
                                .setPositiveButton("Retry") { _, _ -> checkActivation() }
                                .show()
                        }
                    }
                    is RemoteDb.Binding.ClaimedBy ->
                        if (b.androidId == thisDeviceId()) startPolling()
                        else showActivationBlock(b.label)
                }
            }
        }
    }

    private fun showActivationBlock(otherLabel: String) {
        subtitle.text = "Locked to another device"
        AlertDialog.Builder(this)
            .setTitle("Panel locked to another device")
            .setMessage("This admin panel is activated on \"$otherLabel\", and only that device can use it.\n\n" +
                "To move the panel here, open it on \"$otherLabel\" and choose ⋮ → \"Release this device\" " +
                "(or delete /devices/__admin__ in the Firebase console), then tap Retry.")
            .setCancelable(false)
            .setNegativeButton("Log out") { _, _ -> logout() }
            .setPositiveButton("Retry") { _, _ -> checkActivation() }
            .show()
    }

    // ── Push app updates (silent install via Device Owner) ───────────────────
    private fun pushAppUpdate() {
        val presets = prefs.appPresets
        val labels = presets.map { "📦  ${it.first}" }.toMutableList()
        labels.add("➕  Add / edit apps…")
        AlertDialog.Builder(this)
            .setTitle("Push app update")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == presets.size) editAppPresets()
                else choosePushTarget(presets[which].first, presets[which].second)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun choosePushTarget(name: String, url: String) {
        val targets = lastDevices.filterNot { isExempt(it) }
        AlertDialog.Builder(this)
            .setTitle("Install \"$name\"")
            .setMessage("Silently download + install on the tablets (Device Owner — no prompts).\n\n" +
                "Updating an app that's already installed needs the SAME signing key as the installed " +
                "version, or it can only go on as a new app.\n\nURL:\n$url")
            .setNegativeButton("Cancel", null)
            .setNeutralButton("One device…") { _, _ -> pickDeviceForInstall(name, url) }
            .setPositiveButton("All (${targets.size})") { _, _ -> sendInstallToAll(name, url) }
            .show()
    }

    private fun sendInstallToAll(name: String, url: String) {
        val targets = lastDevices.filterNot { isExempt(it) }
        if (targets.isEmpty()) { toast("No target devices"); return }
        val db = RemoteDb(this)
        io.execute {
            var ok = 0
            for (d in targets) if (db.sendInstall(d.id, url, name)) ok++
            ui.post { toast("Install queued on $ok/${targets.size}"); refreshOnce() }
        }
    }

    private fun pickDeviceForInstall(name: String, url: String) {
        val devices = lastDevices
        if (devices.isEmpty()) { toast("No devices"); return }
        val names = devices.map { it.name.ifBlank { it.id } + (if (it.online) "  • online" else "") }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Install \"$name\" on…")
            .setItems(names) { _, which ->
                val d = devices[which]
                val db = RemoteDb(this)
                io.execute {
                    val ok = db.sendInstall(d.id, url, name)
                    ui.post { toast(if (ok) "Queued on ${d.name.ifBlank { d.id }}" else "Failed"); refreshOnce() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editAppPresets() {
        val presets = prefs.appPresets.toMutableList()
        val labels = presets.map { "${it.first}\n${it.second}" }.toMutableList()
        labels.add("➕  Add new app")
        AlertDialog.Builder(this)
            .setTitle("Apps (name + APK URL)")
            .setItems(labels.toTypedArray()) { _, which ->
                addOrEditPreset(if (which == presets.size) null else which)
            }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun addOrEditPreset(index: Int?) {
        val presets = prefs.appPresets.toMutableList()
        val existing = index?.let { presets[it] }
        val nameE = edit("App name (e.g. Z Exam)", InputType.TYPE_CLASS_TEXT).apply { existing?.let { setText(it.first) } }
        val urlE = edit("Direct APK URL (https://…)", InputType.TYPE_TEXT_VARIATION_URI).apply { existing?.let { setText(it.second) } }
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add app" else "Edit app")
            .setView(column(nameE, urlE))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val n = nameE.text.toString().trim(); val u = urlE.text.toString().trim()
                if (n.isBlank() || !u.startsWith("http")) { toast("Enter a name and an https:// URL"); return@setPositiveButton }
                if (index == null) presets.add(n to u) else presets[index] = n to u
                prefs.appPresets = presets
                toast("Saved")
            }
        if (existing != null) b.setNeutralButton("Delete") { _, _ ->
            presets.removeAt(index!!); prefs.appPresets = presets; toast("Deleted")
        }
        b.show()
    }

    private fun confirmRelease() {
        AlertDialog.Builder(this)
            .setTitle("Release this device?")
            .setMessage("Un-links the panel from this tablet so you can activate it on a different one. " +
                "You'll be logged out, and the next device to log in becomes the panel.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Release") { _, _ ->
                val db = RemoteDb(this)
                io.execute {
                    val ok = db.clearBinding()
                    ui.post {
                        if (ok) {
                            toast("Released — the panel can now be activated on another device")
                            doLogout()
                        } else toast("Couldn't release — try again")
                    }
                }
            }
            .show()
    }

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("Lock all devices")
            menu.add("Unlock all devices")
            menu.add("Admin devices (skip \"Lock all\")")
            menu.add("Push app update")
            menu.add("Refresh now")
            menu.add("Release this device")
            menu.add("Log out")
            setOnMenuItemClickListener { item ->
                when (item.title) {
                    "Lock all devices" -> bulk("lock", "Lock ALL devices?")
                    "Unlock all devices" -> bulk("unlock", "Unlock ALL devices?")
                    "Admin devices (skip \"Lock all\")" -> manageAdminDevices()
                    "Push app update" -> pushAppUpdate()
                    "Refresh now" -> refreshOnce()
                    "Release this device" -> confirmRelease()
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
            .setPositiveButton("Log out") { _, _ -> doLogout() }
            .show()
    }

    private fun doLogout() {
        polling = false
        FbAuth.logout(this)
        loggedIn = false
        adapter.submit(emptyList())
        onlinePill.text = "0 online"
        promptLogin()
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
                        adapter.exemptKeys = prefs.exemptKeys
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
                "Re-enables the admin/exit panel on the tablet.") }
        } else {
            labels.add("🔒  Lock (only you can unlock)")
            actions.add { confirmAndSend(d, "lock", "Lock ${d.name}?",
                "Sends the tablet to the MDM home and seals the admin/exit panel so it can't leave the MDM. " +
                "Apps and buttons still work. Only you can unlock it here.") }
        }
        labels.add("📂  Browse files")
        actions.add { openFileBrowser(d) }
        labels.add("🔑  Reset screen lock")
        actions.add { confirmAndSend(d, "reset_screen_lock", "Reset screen lock on ${d.name}?",
            "Clears the tablet's screen PIN/password so a locked-out student can get back in.") }
        labels.add("♻️  Factory reset")
        actions.add { confirmFactoryReset(d) }
        if (isExempt(d)) {
            labels.add("⭐  Admin device — remove flag")
            actions.add { setExempt(d, false) }
        } else {
            labels.add("⭐  Set as admin device (skip bulk actions)")
            actions.add { setExempt(d, true) }
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

    // ── Admin-device exemption (skipped by bulk actions) ─────────────────────
    private fun keyOf(d: RemoteDb.Device): String = d.androidId.ifBlank { d.id }
    private fun isExempt(d: RemoteDb.Device): Boolean = keyOf(d) in prefs.exemptKeys

    /** A clear, dedicated picker for which devices "Lock all" / "Unlock all" should skip. */
    private fun manageAdminDevices() {
        val devices = lastDevices
        if (devices.isEmpty()) { toast("No devices yet — refresh and try again"); return }
        val names = devices.map {
            (it.name.ifBlank { it.id }) + (if (it.online) "  • online" else "")
        }.toTypedArray()
        val checked = devices.map { isExempt(it) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Admin devices — skip \"Lock all\"")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val keys = prefs.exemptKeys.toMutableSet()
                devices.forEachIndexed { i, d -> if (checked[i]) keys.add(keyOf(d)) else keys.remove(keyOf(d)) }
                prefs.exemptKeys = keys
                adapter.exemptKeys = keys
                adapter.submit(lastDevices)
                val n = devices.count { isExempt(it) }
                toast(if (n == 0) "No admin devices — bulk actions hit everything"
                      else "$n admin device(s) will be skipped by Lock all / Unlock all")
            }
            .show()
    }

    private fun setExempt(d: RemoteDb.Device, exempt: Boolean) {
        val keys = prefs.exemptKeys.toMutableSet()
        if (exempt) keys.add(keyOf(d)) else keys.remove(keyOf(d))
        prefs.exemptKeys = keys
        adapter.exemptKeys = keys
        adapter.submit(lastDevices)
        toast(if (exempt) "${d.name.ifBlank { d.id }} set as admin device — bulk actions will skip it"
              else "${d.name.ifBlank { d.id }} is no longer an admin device")
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
        val targets = lastDevices.filterNot { isExempt(it) }
        val exempt = lastDevices.filter { isExempt(it) }
        if (targets.isEmpty()) {
            toast(if (lastDevices.isEmpty()) "No devices yet" else "Only admin device(s) present — nothing to $action")
            return
        }
        val verb = if (action == "lock") "LOCKED" else "UNLOCKED"
        fun names(list: List<RemoteDb.Device>): String {
            val shown = list.take(20).joinToString("\n") { "•  ${it.name.ifBlank { it.id }}" }
            return if (list.size > 20) "$shown\n…and ${list.size - 20} more" else shown
        }
        val sb = StringBuilder("These ${targets.size} device(s) will be $verb:\n${names(targets)}")
        if (exempt.isNotEmpty()) {
            sb.append("\n\n⭐ Skipped (admin device${if (exempt.size > 1) "s" else ""}):\n${names(exempt)}")
        } else {
            sb.append("\n\n⚠ No admin device is set — this will $action EVERY device above, " +
                "including your own control tablet if it's in the list. To exclude it, Cancel and use " +
                "⋮ → \"Admin devices\" first.")
        }
        sb.append("\n\nOffline ones run when they reconnect.")
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(sb.toString())
            .setNegativeButton("Cancel", null)
            .setPositiveButton("$verb ${targets.size}") { _, _ ->
                val db = RemoteDb(this)
                io.execute {
                    var ok = 0
                    for (d in targets) if (db.sendCommand(d.id, action)) ok++
                    ui.post { toast("Sent to $ok/${targets.size}"); refreshOnce() }
                }
            }
            .show()
    }

    private fun openFileBrowser(d: RemoteDb.Device) {
        startActivity(
            android.content.Intent(this, FileBrowserActivity::class.java)
                .putExtra("deviceId", d.id)
                .putExtra("deviceName", d.name.ifBlank { d.id }),
        )
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

    companion object { private const val POLL_MS = 6_000L }
}
