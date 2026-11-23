package com.suguna.admin

/**
 * Public Firebase config — safe to ship. Protection is enforced by the Realtime Database
 * security rules (only the admin login reads the fleet), not by hiding these values.
 */
object Config {
    const val FIREBASE_API_KEY = "AIzaSyAMKqrsk5QLaHulz36-mOgcFD1b_OResjI"
    const val FIREBASE_DB_URL = "https://suguna-admin-eee8b-default-rtdb.europe-west1.firebasedatabase.app"
}
