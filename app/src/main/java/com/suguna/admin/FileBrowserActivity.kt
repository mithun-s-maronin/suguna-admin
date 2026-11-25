package com.suguna.admin

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Remote file browser for one tablet. Sends list/read/write/delete commands and waits for the
 * tablet to publish the result to /devices/<uid>/fileResult. Transfers are small (≤3 MB, base64
 * over the database) — the free-plan trade-off.
 */
class FileBrowserActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var deviceId: String
    private var currentPath = ""
    private val rows = ArrayList<JSONObject>()        // entries for the current dir
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var pathView: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        deviceId = intent.getStringExtra("deviceId") ?: run { finish(); return }
        val deviceName = intent.getStringExtra("deviceName") ?: "device"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1B2A"))
            val p = dp(12); setPadding(p, p, p, p)
        }
        root.addView(TextView(this).apply {
            text = "📂  $deviceName"; setTextColor(Color.WHITE); textSize = 18f
        })
        pathView = TextView(this).apply {
            setTextColor(Color.parseColor("#9FB3C8")); textSize = 12f
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(pathView)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(Button(this).apply { text = "⬆ Up"; setOnClickListener { goUp() } }, barLp())
        bar.addView(Button(this).apply { text = "⟳"; setOnClickListener { navigate(currentPath) } }, barLp())
        bar.addView(Button(this).apply { text = "⬆ Upload"; setOnClickListener { pickUpload() } }, barLp())
        root.addView(bar)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        val list = ListView(this).apply {
            this.adapter = this@FileBrowserActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> onRowTap(pos) }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#9FB3C8")); textSize = 13f; setPadding(0, dp(6), 0, 0)
        }
        root.addView(status)
        setContentView(root)

        navigate("")   // blank → the tablet's default root (/sdcard)
    }

    // ── Navigation ───────────────────────────────────────────────────────────
    private fun navigate(path: String) {
        request("list_dir", path, null) { res ->
            currentPath = res.optString("path", path)
            pathView.text = currentPath
            rows.clear()
            val arr = res.optJSONArray("entries")
            val labels = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i); rows.add(e)
                val icon = if (e.optBoolean("d")) "📁" else "📄"
                val sz = if (e.optBoolean("d")) "" else "   ${human(e.optLong("s"))}"
                labels.add("$icon  ${e.optString("n")}$sz")
            }
            adapter.clear(); adapter.addAll(labels); adapter.notifyDataSetChanged()
            status.text = "${rows.size} item(s)"
        }
    }

    private fun goUp() {
        val p = currentPath.trimEnd('/')
        val parent = p.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) navigate(parent) else toast("Already at the top")
    }

    private fun onRowTap(pos: Int) {
        val e = rows.getOrNull(pos) ?: return
        val name = e.optString("n")
        val full = "${currentPath.trimEnd('/')}/$name"
        if (e.optBoolean("d")) navigate(full) else fileMenu(name, full, e.optLong("s"))
    }

    private fun fileMenu(name: String, full: String, size: Long) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(arrayOf("⬇ Download", "🗑 Delete")) { _, which ->
                when (which) {
                    0 -> download(name, full, size)
                    1 -> confirmDelete(name, full)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ── Operations ─────────────────────────────────────────────────────────
    private fun download(name: String, full: String, size: Long) {
        if (size > 3 * 1024 * 1024) { toast("Too big to pull on the free plan (3 MB max)"); return }
        request("read_file", full, null) { res ->
            val b64 = res.optString("b64")
            if (b64.isBlank()) { status.text = "Empty file"; return@request }
            io.execute {
                val saved = saveToDownloads(name, Base64.decode(b64, Base64.NO_WRAP))
                ui.post { status.text = if (saved) "Saved \"$name\" to Downloads" else "Couldn't save" }
            }
        }
    }

    private fun confirmDelete(name: String, full: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"$name\"?")
            .setMessage("Deletes it from the tablet. Can't be undone.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                request("delete_file", full, null) { toast("Deleted"); navigate(currentPath) }
            }
            .show()
    }

    private fun pickUpload() {
        try {
            startActivityForResult(
                Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),
                REQ_PICK,
            )
        } catch (e: Exception) { toast("No file picker available") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        io.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                if (bytes.size > 3 * 1024 * 1024) { ui.post { toast("Too big (3 MB max)") }; return@execute }
                val name = queryName(uri)
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                ui.post {
                    request("write_file", "${currentPath.trimEnd('/')}/$name", b64) {
                        toast("Uploaded \"$name\""); navigate(currentPath)
                    }
                }
            } catch (e: Exception) { ui.post { toast("Upload failed: ${e.message}") } }
        }
    }

    // ── request/response over the database ───────────────────────────────────
    private fun request(action: String, path: String, b64: String?, onOk: (JSONObject) -> Unit) {
        status.text = "Working…"
        io.execute {
            val db = RemoteDb(this)
            val reqId = db.sendFileCmd(deviceId, action, path, b64)
            if (reqId == null) { ui.post { status.text = "Couldn't send (not logged in?)" }; return@execute }
            var result: JSONObject? = null
            for (i in 0 until 25) {                       // ~20s
                try { Thread.sleep(800) } catch (e: InterruptedException) { break }
                val r = db.readFileResult(deviceId)
                if (r != null && r.optString("reqId") == reqId) { result = r; break }
            }
            val res = result
            ui.post {
                when {
                    res == null -> status.text = "No response — is the tablet online?"
                    res.optBoolean("needsAccess") -> promptGrantAccess()
                    !res.optBoolean("ok") -> status.text = "Error: ${res.optString("error")}"
                    else -> onOk(res)
                }
            }
        }
    }

    private fun promptGrantAccess() {
        status.text = "Tablet needs \"All files access\""
        AlertDialog.Builder(this)
            .setTitle("Grant file access on the tablet")
            .setMessage("This tablet hasn't allowed \"All files access\" yet. Tap Send to open that " +
                "settings screen ON THE TABLET, then someone at the tablet turns it on for Sure MDM. " +
                "After that, come back and retry.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Send") { _, _ ->
                io.execute {
                    RemoteDb(this).sendFileCmd(deviceId, "grant_file_access", "")
                    ui.post { toast("Sent — grant it on the tablet, then retry") }
                }
            }
            .show()
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private fun saveToDownloads(name: String, bytes: ByteArray): Boolean = try {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) { contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }; true } else false
        } else {
            // Older devices: save into the app's own Downloads folder.
            val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            java.io.File(dir, name).writeBytes(bytes); true
        }
    } catch (e: Exception) { false }

    private fun queryName(uri: Uri): String {
        var name = "upload_${System.currentTimeMillis()}"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
            }
        } catch (e: Exception) {}
        return name
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun barLp() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private companion object { const val REQ_PICK = 101 }
}
