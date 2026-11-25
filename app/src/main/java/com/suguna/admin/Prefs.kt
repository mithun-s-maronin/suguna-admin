package com.suguna.admin

import android.content.Context

/** Local settings for the admin app: the logged-in admin email and the Firebase session token. */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("suguna_admin", Context.MODE_PRIVATE)

    /** The admin email currently logged in (shown in the header). */
    var adminEmail: String
        get() = sp.getString("admin_email", "") ?: ""
        set(v) = sp.edit().putString("admin_email", v.trim()).apply()

    /** Firebase refresh token for the admin session — blank = logged out. */
    var fbRefreshToken: String
        get() = sp.getString("fb_refresh", "") ?: ""
        set(v) = sp.edit().putString("fb_refresh", v).apply()

    /**
     * Device keys (stable androidId, or record id as fallback) that are exempt from bulk actions —
     * typically the admin's own tablet, so "Lock all" never locks the device you're controlling from.
     */
    var exemptKeys: Set<String>
        get() = (sp.getString("exempt_keys", "") ?: "")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        set(v) = sp.edit().putString("exempt_keys", v.joinToString(",")).apply()

    /** Saved app-update entries: (name, download URL). Each line is "name|url". */
    var appPresets: List<Pair<String, String>>
        get() = (sp.getString("app_presets", "") ?: "").split("\n").mapNotNull {
            val i = it.indexOf('|'); if (i <= 0 || i == it.length - 1) null else it.substring(0, i) to it.substring(i + 1)
        }
        set(v) = sp.edit().putString("app_presets", v.joinToString("\n") { "${it.first}|${it.second}" }).apply()
}
