package com.suguna.admin

import android.content.Context
import java.security.MessageDigest

/** Local settings for the admin app: the Firebase config, the admin password, and the device lock. */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("suguna_admin", Context.MODE_PRIVATE)

    /** Firebase Realtime Database base URL, e.g. https://xxxx-default-rtdb.firebasedatabase.app */
    var dbUrl: String
        get() = sp.getString("db_url", "") ?: ""
        set(v) = sp.edit().putString("db_url", v.trimEnd('/')).apply()

    /** Firebase database auth secret (legacy database secret) used as ?auth=… for REST calls. */
    var dbSecret: String
        get() = sp.getString("db_secret", "") ?: ""
        set(v) = sp.edit().putString("db_secret", v.trim()).apply()

    /** SHA-256 of the admin password. Blank until first-run setup. */
    var passwordHash: String
        get() = sp.getString("pw_hash", "") ?: ""
        set(v) = sp.edit().putString("pw_hash", v).apply()

    /** The ANDROID_ID this app was first set up on. The app refuses to run on any other device. */
    var boundDeviceId: String
        get() = sp.getString("bound_device", "") ?: ""
        set(v) = sp.edit().putString("bound_device", v).apply()

    val isConfigured: Boolean
        get() = dbUrl.isNotBlank() && dbSecret.isNotBlank() && passwordHash.isNotBlank() && boundDeviceId.isNotBlank()

    fun checkPassword(input: String): Boolean = sha256(input) == passwordHash

    fun setPassword(input: String) { passwordHash = sha256(input) }

    companion object {
        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
