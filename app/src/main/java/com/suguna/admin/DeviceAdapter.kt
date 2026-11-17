package com.suguna.admin

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceAdapter(
    private val onClick: (RemoteDb.Device) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val items = ArrayList<RemoteDb.Device>()
    private val timeFmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

    fun submit(list: List<RemoteDb.Device>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val dot: View = v.findViewById(R.id.dot)
        val name: TextView = v.findViewById(R.id.name)
        val meta: TextView = v.findViewById(R.id.meta)
        val status: TextView = v.findViewById(R.id.status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val d = items[position]
        h.name.text = d.name.ifBlank { d.id }
        val last = if (d.lastSeen > 0) timeFmt.format(Date(d.lastSeen)) else "never"
        val enroll = if (d.enrollment.isNotBlank()) "Enroll ${d.enrollment} • " else ""
        val batt = if (d.battery in 0..100) " • ${d.battery}%" else ""
        val app = if (d.currentApp.isNotBlank()) "\nApp: ${d.currentAppLabel}" else ""
        val loc = if (d.hasLocation) "\n📍 ${"%.5f".format(d.lat)}, ${"%.5f".format(d.lng)}" +
            (if (d.locAccuracy > 0) " (±${d.locAccuracy}m)" else "") else ""
        val lock = if (d.permanentlyLocked) "\n🔒 Permanently locked" else ""
        h.meta.text = "$enroll$last$batt$app$loc$lock"
        val colorRes = if (d.online) R.color.online else R.color.offline
        val color = h.itemView.context.getColor(colorRes)
        h.dot.setBackgroundColor(color)
        h.status.text = if (d.online) "ONLINE" else "OFFLINE"
        h.status.setTextColor(color)
        h.itemView.setOnClickListener { onClick(d) }
    }
}
