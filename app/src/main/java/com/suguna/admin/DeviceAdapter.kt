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
        val appLine: TextView = v.findViewById(R.id.appLine)
        val locLine: TextView = v.findViewById(R.id.locLine)
        val lockBadge: TextView = v.findViewById(R.id.lockBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val ctx = h.itemView.context
        val d = items[position]
        h.name.text = d.name.ifBlank { d.id }

        val last = if (d.lastSeen > 0) timeFmt.format(Date(d.lastSeen)) else "never"
        val enroll = if (d.enrollment.isNotBlank()) "Enroll ${d.enrollment}  •  " else ""
        val batt = if (d.battery in 0..100) "  •  🔋 ${d.battery}%" else ""
        h.meta.text = "$enroll$last$batt"

        val color = ctx.getColor(if (d.online) R.color.online else R.color.offline)
        h.dot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        h.status.text = if (d.online) "ONLINE" else "OFFLINE"
        h.status.setTextColor(color)
        h.status.setBackgroundResource(if (d.online) R.drawable.chip_online else R.drawable.chip_offline)

        if (d.currentApp.isNotBlank()) {
            h.appLine.text = "Current app:  ${d.currentAppLabel}"
            h.appLine.visibility = View.VISIBLE
        } else h.appLine.visibility = View.GONE

        if (d.hasLocation) {
            val acc = if (d.locAccuracy > 0) "  (±${d.locAccuracy}m)" else ""
            h.locLine.text = "📍 ${"%.5f".format(d.lat)}, ${"%.5f".format(d.lng)}$acc"
            h.locLine.visibility = View.VISIBLE
        } else h.locLine.visibility = View.GONE

        h.lockBadge.visibility = if (d.permanentlyLocked) View.VISIBLE else View.GONE

        h.itemView.setOnClickListener { onClick(d) }
    }
}
