# Suguna Admin — remote monitor & control

A small Android app, locked to one device and password-protected, that shows your managed
tablets (running the `com.suguna.home` fork) as **online/offline** and can **Lock** or
**Factory reset** any tablet that is currently online.

It talks to a free **Firebase Realtime Database** over REST (short poll, ~10s). No server,
no hosting, no Firebase SDK, no `google-services.json`. Works from anywhere over the internet.

---

## 1. Create the Firebase project (free, ~5 min)

1. Go to <https://console.firebase.google.com> → **Add project** (e.g. `suguna-admin`).
   Google Analytics can be left off.
2. In the left menu: **Build → Realtime Database → Create Database**.
   - Pick a location (e.g. Singapore).
   - Start in **Locked mode**.
3. Copy the **Database URL** shown at the top of the Realtime Database page. It looks like:
   `https://suguna-admin-default-rtdb.asia-southeast1.firebasedatabase.app`
4. Get a **database secret**: gear icon → **Project settings → Service accounts →
   Database secrets** → **Show**/**Add secret**. Copy it.
   - (If "Database secrets" is hidden, click the "…"/legacy link on that page to reveal it.)
5. Set the database **Rules** to this (keeps everyone out except holders of the secret):

   ```json
   { "rules": { ".read": false, ".write": false } }
   ```
   The secret used by both apps bypasses these rules, so the apps still work while the
   database stays private to the public.

You now have two values you'll need: **Database URL** and **database secret**.

---

## 2. Build the admin app (on your ROG laptop)

1. Install **Android Studio** (if not already).
2. **File → Open** this `SugunaAdmin` folder. Let Gradle sync (it downloads everything).
3. Plug in your phone (USB debugging on) and press **Run**, or **Build → Build APK(s)**.
4. First launch asks you to:
   - set an **admin password**,
   - paste the **Database URL** and **database secret**.
   It then **locks itself to that phone** — it won't function on any other device, and it
   asks for the password every time it opens.

> One-device lock / password: Android can't stop an APK being *installed* elsewhere, but the
> app refuses to run on any device other than the first one it was set up on, and is useless
> without the password. Self-deleting its own installer isn't reliable on modern Android;
> install it via `adb install` and no file is left behind.

---

## 3. Wire the tablets (the `com.suguna.home` fork)

See `../fork-remote-admin/INTEGRATION.md`. In short: add `RemoteAdminService.kt`, fill in the
**same** Database URL + secret, declare it in the manifest with the `INTERNET` and
`FOREGROUND_SERVICE` permissions, and start it from `LauncherActivity` and on boot. Each
tablet then appears automatically in the admin app within ~45s.

---

## What you can do / add later
- Live **online / offline** per tablet (green/red), with name, enrollment, battery, last-seen.
- **Lock now** and **Factory reset** (double-confirm) for any online tablet.
- Easy to extend with more commands (reboot, relaunch kiosk, clear app data, show a message) —
  add the action in `RemoteDb.sendCommand` callers and handle it in `RemoteAdminService.pollCommand`.
