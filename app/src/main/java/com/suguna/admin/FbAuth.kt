package com.suguna.admin

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Admin-side Firebase Authentication over REST (email + password) — no Firebase SDK.
 *
 * The admin logs in with the email/password that the security rules trust. The refresh token is
 * persisted so the session survives app restarts; short-lived ID tokens are minted from it and
 * sent as ?auth=<idToken> on every Realtime Database call.
 */
object FbAuth {

    private const val TAG = "FbAuth"
    private const val SIGNIN = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword"
    private const val SIGNUP = "https://identitytoolkit.googleapis.com/v1/accounts:signUp"
    private const val SECURETOKEN = "https://securetoken.googleapis.com/v1/token"

    @Volatile private var cachedToken: String? = null
    @Volatile private var expiresAt: Long = 0L

    sealed class LoginResult {
        object Ok : LoginResult()
        object Created : LoginResult()
        data class Error(val message: String) : LoginResult()
    }

    /**
     * Log in. First-run creates the account. Because Firebase's email-enumeration protection
     * hides whether an account exists (sign-in returns a generic "invalid credentials"), we try
     * sign-in first, and on any non-network failure attempt to create the account. If creation
     * reports the email already exists, the password was simply wrong.
     */
    fun login(context: Context, email: String, password: String): LoginResult {
        val body = JSONObject()
            .put("email", email).put("password", password).put("returnSecureToken", true).toString()

        // 1. Try to sign in to an existing account.
        val (code, resp) = post("$SIGNIN?key=${Config.FIREBASE_API_KEY}", body)
        if (resp != null && code in 200..299) { store(context, resp); return LoginResult.Ok }

        val signInErr = errorCode(resp)
        if (signInErr == "NETWORK") return LoginResult.Error(friendly("NETWORK"))

        // 2. Sign-in failed. Try to create the account (first-run). If it already exists, the
        //    password was wrong; otherwise surface the real reason (weak password, bad email…).
        val (c2, r2) = post("$SIGNUP?key=${Config.FIREBASE_API_KEY}", body)
        if (r2 != null && c2 in 200..299) { store(context, r2); return LoginResult.Created }

        val signUpErr = errorCode(r2)
        return when {
            signUpErr.startsWith("EMAIL_EXISTS") -> LoginResult.Error("Wrong password")
            signUpErr == "NETWORK" -> LoginResult.Error(friendly("NETWORK"))
            else -> LoginResult.Error(friendly(signUpErr))
        }
    }

    /** A valid ID token for the logged-in admin, refreshing as needed. Null if logged out/offline. */
    @Synchronized
    fun idToken(context: Context): String? {
        if (cachedToken != null && System.currentTimeMillis() < expiresAt - 60_000) return cachedToken
        val rt = Prefs(context).fbRefreshToken
        if (rt.isBlank()) return null
        val (code, resp) = post("$SECURETOKEN?key=${Config.FIREBASE_API_KEY}",
            "grant_type=refresh_token&refresh_token=$rt", form = true)
        if (resp == null || code !in 200..299) return null
        return try {
            val o = JSONObject(resp)
            Prefs(context).fbRefreshToken = o.getString("refresh_token")
            cache(o.getString("id_token"), o.optString("expires_in", "3600"))
            cachedToken
        } catch (e: Exception) { Log.w(TAG, "refresh parse: ${e.message}"); null }
    }

    fun isLoggedIn(context: Context): Boolean = Prefs(context).fbRefreshToken.isNotBlank()

    fun logout(context: Context) {
        Prefs(context).fbRefreshToken = ""
        cachedToken = null; expiresAt = 0L
    }

    private fun store(context: Context, resp: String) {
        val o = JSONObject(resp)
        Prefs(context).fbRefreshToken = o.getString("refreshToken")
        Prefs(context).adminEmail = o.optString("email", Prefs(context).adminEmail)
        cache(o.getString("idToken"), o.optString("expiresIn", "3600"))
    }

    private fun cache(token: String, expiresIn: String) {
        cachedToken = token
        expiresAt = System.currentTimeMillis() + (expiresIn.toLongOrNull() ?: 3600) * 1000
    }

    private fun errorCode(resp: String?): String = try {
        if (resp == null) "NETWORK" else JSONObject(resp).optJSONObject("error")?.optString("message") ?: "UNKNOWN"
    } catch (e: Exception) { "UNKNOWN" }

    private fun friendly(code: String): String = when {
        code.startsWith("INVALID_PASSWORD") || code.startsWith("INVALID_LOGIN") -> "Wrong password"
        code.startsWith("INVALID_EMAIL") -> "That email isn't valid"
        code.startsWith("WEAK_PASSWORD") -> "Password too weak (min 6 characters)"
        code.startsWith("EMAIL_EXISTS") -> "Account exists — check your password"
        code == "NETWORK" -> "No connection — check internet"
        else -> "Login failed ($code)"
    }

    private fun post(url: String, body: String, form: Boolean = false): Pair<Int, String?> = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 20000
        c.doOutput = true
        c.setRequestProperty("Content-Type",
            if (form) "application/x-www-form-urlencoded" else "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val t = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }
        c.disconnect(); code to t
    } catch (e: Exception) { Log.w(TAG, "post: ${e.message}"); -1 to null }
}
