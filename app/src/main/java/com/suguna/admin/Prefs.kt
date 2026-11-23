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
}
