# ShieldChild – Total Review & Improvement Plan

> **Audience:** an AI coding agent (or developer) executing fixes in this repo.
> **Reviewed:** commit `e93b6d3` (main), 16 Oct 2026. Read-only review; nothing was changed.
> **How to use:** work top to bottom. Each task has an ID, the files involved, what is wrong, what to do and an **acceptance check**. Do one task per commit (`fix(SEC-1): …`). Do not start a P1 task while a P0 task is still open.

Legend: **P0** = ship-blocker / kid can bypass / parent locked out · **P1** = feature broken or misleading · **P2** = quality, UX, maintainability · **P3** = nice to have.
"Verified" = confirmed by reading the code. "Verify on device" = strong suspicion from the code; confirm on a real phone before and after the fix.

---

## 0. Context the agent needs

- Android-only, Kotlin + Jetpack Compose, single module `app/`. `minSdk 26`, `targetSdk 36`.
- Enforcement: `ShieldMonitorService` (foreground service, 500 ms loop) + `ShieldAccessibilityService` (window events) → `PolicyEngine` → `CurfewLockActivity` (whole phone) or `AppShieldOverlayManager` (single app).
- Storage: plain `SharedPreferences` in `PreferencesRepository` (`shield_child_secure_prefs`).
- `src/engine/policyRules.ts` + `tests/policyRules.test.ts` (Jest) are a **TypeScript re-implementation** of the rules. They do not test the Kotlin code that actually runs on the phone.
- `app/.../bridge/ShieldBridgeModule.kt` is a leftover of the removed React Native layer and is **not referenced anywhere**.
- The owner is a JavaScript/React Native developer. They did **not** ask for React Native to be removed (see the Antigravity conversation). Keep that in mind for task ARCH-3.
- A public GitHub release `v1.0.0` already exists, and the repo is **public**.

---

## 1. P0 – Security holes (fix first, then publish v1.0.1)

### SEC-1 · Every install has the same recovery key, and it is published  — *Verified*
- **Files:** `data/PreferencesRepository.kt:33-43, 203`, `engine/ParentAuthManager.kt:66-99`, `README.md:43, 94`, `docs/index.html`, Security tab in `ParentDashboardActivity.kt` (~line 2167).
- **Problem:** `getMasterRecoveryKey()` returns the constant `DEFAULT_RECOVERY_KEY = "SHLD-9A4F-K82X-7M1P"` and nothing ever generates a random one (`saveMasterRecoveryKey` is never called). The README prints this exact key. Any child who reads the README, or any kid who once sees it, can reset the parent PIN from the lock screen on **every** ShieldChild phone.
- Also: the raw key is stored in plain text (`KEY_RECOVERY_KEY_RAW`). `recoverWithMasterKey` compares against the raw value, so the hash saved by `setMasterRecoveryKey` is never used.
- **Do:**
  1. Generate a random key at setup with `SecureRandom` (16 chars from an unambiguous alphabet such as `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`, formatted `XXXX-XXXX-XXXX-XXXX`).
  2. Show it **once** in onboarding (a "write this down" step, with a checkbox to confirm), and store only `PBKDF2(key, salt)` (see SEC-3). Never store or redisplay the raw key. The Security tab can offer "Generate new recovery key" behind parent auth.
  3. Verify by hashing the input and comparing in constant time.
  4. Apply the same lockout counter to recovery-key attempts as to PIN attempts.
  5. Migration: on upgrade, if `KEY_RECOVERY_KEY_RAW` equals the old default, delete it and force the parent to generate a new key on the next dashboard open.
  6. Remove the literal key from the README and docs. Use `XXXX-XXXX-XXXX-XXXX` as the example.
- **Accept:** `grep -r "9A4F" .` returns nothing. Two fresh installs show different keys. The old key fails on an upgraded install.

### SEC-2 · Hidden default PIN "1234"  — *Verified*
- **File:** `engine/ParentAuthManager.kt:37-45`
- **Problem:** if no PIN hash exists, entering `1234` succeeds and becomes the PIN. A PIN can be missing after Clear Storage, or if onboarding is skipped or crashes.
- **Do:** delete the fallback. With no PIN configured, `verifyPin` returns a new `AuthResult.NotConfigured`, and the UI routes to onboarding, which itself must require auth (see SEC-5).
- **Accept:** a unit test confirms that `verifyPin("1234")` with empty storage is not `Success`.

### SEC-3 · Weak PIN hashing  — *Verified*
- **File:** `ParentAuthManager.kt:101-106`
- **Problem:** one round of SHA-256 over a 4–8 digit PIN can be brute-forced instantly by anyone who can read the prefs file (rooted device or backup).
- **Do:** use `PBKDF2WithHmacSHA256` with at least 120k iterations and a 16-byte salt (on-device stdlib, no new dependency). Store `algo$iterations$salt$hash` so it can be upgraded later. Re-hash on the next successful login when the old format is found.
- **Accept:** unit test for round-trip and for legacy-format migration.

### SEC-4 · Changing the phone's clock or time zone bypasses the curfew  — *Verified (logic) / verify on device*
- **Files:** `PolicyEngine.isWithinCurfewWindow`, `PreferencesRepository.resetDailyCountersIfNewDay`, `ShieldAccessibilityService.kt:31-58`
- **Problem:** all decisions use `Calendar.getInstance()` / `System.currentTimeMillis()`. A child can turn off "automatic date & time" and set the clock to 08:00 to end bedtime, or move it to tomorrow to reset the daily counters and bonus. The Date & Time settings page doesn't contain the text "ShieldChild", so tamper detection doesn't block it.
- **Do:**
  1. In the accessibility tamper check, also block the Date & Time settings screen (match its activity class or title, e.g. `DateTimeSettings`) unless parent uninstall/config mode is active.
  2. Register a receiver for `ACTION_TIME_CHANGED` and `ACTION_TIMEZONE_CHANGED`. On an unexpected jump of more than 2 minutes, set the instant lock and record a tamper event for the parent.
  3. Track usage with `SystemClock.elapsedRealtime()` deltas between checks rather than wall-clock time alone.
- **Accept:** with the curfew active, changing the time manually leaves the phone locked, and the parent sees a tamper notice.

### SEC-5 · Clear Storage / Safe Mode / ADB reset everything  — *Verified (logic)*
- **Files:** `ShieldAccessibilityService.kt`, `ShieldDeviceAdminReceiver.kt`, onboarding.
- **Problem:**
  - After "Clear storage", `isSetupCompleted()` is false. All enforcement stops and the app opens to first-time setup, where the child can set **their own** PIN. The tamper check only catches this if the App Info page is open with the text "ShieldChild" visible. Launcher shortcuts ("App info" or "Uninstall" via drag) come from the launcher package, which is not checked.
  - In Safe Mode, third-party accessibility services and admin enforcement are off. `DISALLOW_SAFE_BOOT` only applies as Device Owner, and nothing in the app provisions Device Owner.
- **Do:**
  1. Add the default launcher package (resolve `ACTION_MAIN/CATEGORY_HOME`) to the tamper-check package list, so a launcher "App info" or uninstall popup that mentions ShieldChild triggers the deflection.
  2. Document a **Device Owner setup path** (`adb shell dpm set-device-owner com.shieldchild.app/.service.ShieldDeviceAdminReceiver` on a phone with no accounts, or QR provisioning). Call `applyDeviceOwnerRestrictions()` after it (today it only runs from `onProfileProvisioningComplete`, which ADB setup never triggers). Show "Hardened mode: ON/OFF" in the Security tab.
  3. Be honest in the README (DOC-1) about what is and isn't protected without Device Owner.
- **Accept:** a written test matrix (TEST-3) is filled in for: clear storage, launcher uninstall, safe mode, and ADB, with and without Device Owner.

### SEC-6 · Hard-coded keystore password in a public repo  — *Verified*
- **File:** `app/build.gradle.kts:20-27`
- **Problem:** `storePassword`/`keyPassword = "shieldchild123"` is committed to a public repo. The `.jks` file is gitignored (good), but the password is weak and public.
- **Do:** read `SHIELD_STORE_PASSWORD`, `SHIELD_KEY_PASSWORD` and `SHIELD_KEY_ALIAS` from `local.properties` or environment variables, and skip the release `signingConfig` when they're missing. Owner action: create a new keystore with a strong password, or rotate the password (`keytool -storepasswd` and `-keypasswd`), and keep the `.jks` backed up offline. Note: changing the signing key forces users to uninstall and reinstall.
- **Accept:** `grep -rn "shieldchild123" .` returns nothing. `./gradlew assembleRelease` works with the environment variables set.

### SEC-7 · Lock activity and boot receiver are exported  — *Verified*
- **File:** `AndroidManifest.xml:56-65, 107-121`
- **Problem:** `CurfewLockActivity` is `exported="true"` but only needs to be started by this app. Other apps can launch it with arbitrary extras. `BootReceiver` lists `android.intent.action.REBOOT` (system-only) and `MY_PACKAGE_REPLACED`, and the vendor quick-boot actions can be sent by anyone.
- **Do:** set `CurfewLockActivity` to `exported="false"`. On `BootReceiver`, keep `exported="true"` (needed for `BOOT_COMPLETED`), but drop `REBOOT` and ignore any action that isn't a real boot action. The receiver must only ever *lock*, never unlock (true today; add a comment).

### SEC-8 · The child's own fingerprint passes "parent" biometric checks  — *Verified* — **P0**
- **Files:** `engine/BiometricAuthHelper.kt`, `ParentDashboardActivity.kt` (gate auto-prompt ~3359, Reveal/Copy recovery key ~2457/2475), `CurfewLockActivity.kt:88-104` (+15/+30 min approval)
- **Problem:** `BiometricPrompt` uses `BIOMETRIC_STRONG` with no `CryptoObject`, so **any** fingerprint enrolled on the phone passes, including the child's. On the child's phone, the child can open the dashboard, reveal the recovery key and approve their own extra time. `isBiometricEnabled()` defaults to `true` and is never read.
- **Do:** make fingerprint opt-in and off by default. When the parent enables it, create a Keystore key with `setUserAuthenticationRequired(true)` and `setInvalidatedByBiometricEnrollment(true)`, and authenticate with a `CryptoObject` bound to it. Any fingerprint enrolled later (e.g. the child's) then invalidates the key, and the app falls back to the PIN. Explain in the UI: "Only enable if the child has no fingerprint on this phone." Always require the **PIN** (not biometric) to reveal or regenerate the recovery key and to uninstall.
- **Accept:** enroll a second finger after enabling → the fingerprint is rejected and the PIN is required.

### SEC-9 · "Rerun setup" opens an unauthenticated setup screen  — *Verified* — **P0**
- **Files:** `ParentDashboardActivity.kt:162-173, 211`, `FirstTimeSetupScreen.kt:~393`
- **Problem:** `onRerunSetup` sets `isSetupCompleted = false`. The setup branch is checked **before** the auth check, so the setup screen stays reachable after `onStop` clears auth. Setup then calls `setMasterPin` without the old PIN. If the parent leaves it open, the child sets a new PIN.
- **Do:** render setup only when `!repo.isSetupCompleted() || isParentAuthenticated`. Rerun keeps `setupCompleted=true` in storage and asks for the current PIN before any step that changes credentials.

### SEC-10 · Change PIN / Uninstall flows  — *Verified* — **P1**
- **Change PIN** (`ParentDashboardActivity.kt` ~2849) asks for neither the current PIN nor a confirmation. Add both.
- **Uninstall** (~3150-3160) calls `removeActiveAdmin` and opens a 60 s uninstall window *before* the system uninstall dialog. If the parent cancels, admin stays off and the child has 60 s. Re-activate admin in `onResume` if the app is still installed, or prompt to re-enable it.
- **Gate PIN field** (~3448-3462) runs `verifyPin` on every keystroke from 4 digits. Each extra digit of a 5–8 digit PIN counts as a failed attempt, and one typo locks the parent out for 15 minutes. Verify only on submit (or once the input reaches the stored PIN length, if you store the length).
- **FLAG_SECURE** is missing on the dashboard and lock screens, so the recovery key and PIN screens appear in Recents and screenshots. Add `window.setFlags(FLAG_SECURE, FLAG_SECURE)`. Mark copied secrets with `ClipDescription.EXTRA_IS_SENSITIVE`, or remove the copy button.
- In the dashboard's own lock check (~96), a curfew override returns early, before the instant lock is checked. Instant lock must win (it already does in `CurfewLockLauncher`).

---

## 2. P0/P1 – Broken enforcement logic

### BUG-1 · "Disable daily limit" and "Unlimited days" do nothing  — *Verified* — **P0**
- **Files:** `engine/PolicyEngine.kt:42-51`, `data/PolicyModels.kt:39-41`
- **Problem:** `evaluateLockState` checks only `effectiveDailyLimitMinutes > 0`. It never checks `isDailyLimitEnabled` or `unlimitedDays`, and `isWholeDayAllowed()` is never called. The monitor service therefore locks the phone on Saturdays and Sundays and when the parent turned the limit off. `CurfewLockLauncher.evaluateRebootLockReason` does check both, so the two code paths disagree.
- **Do:** create one pure function `PolicyEngine.evaluateDeviceLock(policy, usedMinutes, now: Calendar, overrideActive): LockReason?`, used by `evaluateLockState`, `evaluateRebootLockReason`, the accessibility service and `ShieldApplication`. Put the day name and "whole day allowed" logic there.
- **Decide:** does an unlimited day also skip the bedtime curfew? Currently it doesn't. Add a separate per-day flag for the curfew if needed and say so in the UI.
- **Accept:** Kotlin unit tests (TEST-1) cover: limit disabled, unlimited day, normal day, and an overnight curfew that spans the day change.

### BUG-2 · Parent "Unlock" with PIN is undone within 0.5 s  — *Verified (logic) / verify on device* — **P1**
- **Files:** `ui/curfew/CurfewLockActivity.kt:73-82` vs `:88-104`
- **Problem:** a successful PIN unlock only clears `instantLock` and `dailyExceeded`, then calls `finish()`. If bedtime or the daily limit still applies, the next monitor tick relaunches the lock. The fingerprint unlock instead grants +30 min and an override. Two paths that look the same behave differently.
- **Do:** give the parent an explicit choice after either kind of auth: "+15 min", "+30 min", "+1 h", "Unlock until tomorrow" (override until the curfew end or midnight) or "Open dashboard". Both PIN and fingerprint go through the same `grantAccess(duration)` function.
- **Accept:** after a PIN unlock during bedtime, the phone stays unlocked for the chosen duration.

### BUG-3 · Single-app lock overlay is added from a background thread  — *Verify on device* — **P0**
- **Files:** `service/ShieldMonitorService.kt:31, 96-109`, `overlay/AppShieldOverlayManager.kt`
- **Problem:** `evaluateCurrentState()` runs on `Dispatchers.Default`. `WindowManager.addView` and `removeView` must run on a thread with a Looper (the main thread). Off the main thread they throw, the empty `catch (_: Exception) {}` hides the error, and the app lock may never appear or never go away. `dismiss()` is also called from the background on every unlocked tick.
- **Also:** if overlay permission is missing, `showAppLock` returns silently and the app isn't locked at all. There's no fallback and no warning to the parent.
- **Do:** run overlay calls on `Dispatchers.Main` (`withContext(Dispatchers.Main)`) or post to `Handler(Looper.getMainLooper())`. As a fallback when the overlay can't be shown, have the accessibility service `performGlobalAction(GLOBAL_ACTION_HOME)` and show a toast. Show a "Protection degraded" banner on the dashboard whenever overlay, usage access, accessibility or admin is missing.
- **Accept:** set a 1-minute limit on a test app → it's blocked after 1 min, the overlay disappears on Home, and logcat shows no exceptions.

### BUG-4 · Emergency SOS is likely covered by the lock immediately  — *Verify on device* — **P0 (child safety)**
- **Files:** `CurfewLockActivity.launchEmergencyDialer`, `ShieldAccessibilityService.checkAndEnforceCurfew`, `ShieldMonitorService`
- **Problem:** `isLaunchingEmergency` only lives inside the activity. When the dialer comes to the front, the accessibility service (any window change) and the 500 ms monitor loop both call `CurfewLockLauncher.launch(...)`, which can cover the dialer or the in-call screen.
- **Do:**
  1. Keep an allow-list of packages that are never covered while locked: the default dialer (`TelecomManager.defaultDialerPackage`), `com.android.server.telecom`, the emergency dialer, and the in-call UI.
  2. Use `Intent.ACTION_DIAL` with the emergency number, or better, the system emergency dialer (`com.android.phone.EmergencyDialer.DIAL`). Don't relaunch the lock while `TelephonyManager.callState != IDLE`.
  3. Separate **Emergency number** (local default by SIM country: 999/995 for Singapore, 112, 911…) from **Parent phone** (used for "Text parent" and "Call parent"). Today one field `emergencyPhoneNumber` (default `"911"`) is used for both, so the "+15 min" SMS is sent to 911.
- **Accept:** with the phone locked, a test call to the parent number connects and stays on screen. When the call ends, the lock returns.

### BUG-5 · Usage time is undercounted  — *Verified* — **P1**
- **File:** `engine/UsageMonitor.kt`
- **Problems:**
  - Time is floored to whole minutes **per app** before summing (`millis / 60000`), so ten apps at 59 s each count as 0. Sum the milliseconds first, then convert.
  - A session that started before midnight and is still open is dropped (no `RESUMED` after 00:00). Treat the first `PAUSED`/`STOPPED` with no matching resume as starting at `startOfDay`.
  - Launcher exclusions are hard-coded to Pixel/AOSP. The OnePlus, Samsung and Xiaomi launchers count as usage while other phones don't. Resolve the default home package at runtime and exclude it, plus input methods (`InputMethodManager.enabledInputMethodList`).
  - Some apps use several activities: `RESUMED` A → `RESUMED` B → `PAUSED` A gets keyed by package and breaks the count. Track by `className`, or count per-package foreground intervals using `MOVE_TO_FOREGROUND`/`MOVE_TO_BACKGROUND` pairs.
  - Consider `UsageStatsManager.queryEventStats(INTERVAL_DAILY)` together with `SCREEN_INTERACTIVE`/`SCREEN_NON_INTERACTIVE` events for the total device time.
- **Accept:** a unit test feeds a fake event list and checks the totals, and the dashboard figure is within ±2 min of Android's Digital Wellbeing over a day.

### BUG-6 · "Daily limit exceeded" flag goes stale  — *Verified* — **P1**
- **Files:** `ShieldMonitorService.kt:93`, `CurfewLockLauncher.kt:41`, `ShieldAccessibilityService.kt:78-90`
- **Problem:** the flag is only cleared by a parent unlock or a new day. During bedtime the monitor writes `false`, and on Unlocked it doesn't write at all. If the parent raises the limit in the dashboard, the accessibility service still sees `exceeded=true` and relaunches the lock on every window change.
- **Do:** remove the persisted flag and recompute from usage, or set it from the result of every evaluation, including Unlocked. After any policy change, call `ACTION_RECHECK_POLICIES` and recompute.

### BUG-7 · Monitor loop drains the battery and spams notifications  — *Verified* — **P1**
- **File:** `ShieldMonitorService.kt:67-74, 142`
- **Problem:** every 500 ms it reads every usage event since midnight (gets slower through the day). While locked, it also calls `startActivity` and re-posts the full-screen notification every 500 ms.
- **Do:**
  - Let the accessibility events drive app switches. Run the loop every **15–30 s** for limit countdowns, only while the screen is on (listen for `ACTION_SCREEN_ON`/`OFF`). Cache the usage total and add elapsed time to it, re-querying fully only every few minutes.
  - Schedule exact wake-ups with `AlarmManager.setExactAndAllowWhileIdle` (permission is already declared) for the curfew start time and for "limit will be reached at T".
  - Launch the lock only on a state *change* (keep `lastLockReason`). Post the notification once.
- **Accept:** battery use per day under ~3% on a test phone (Settings → Battery). The lock still triggers within 2 s of the limit.

### BUG-8 · Thread-safety and lifetime issues  — *Verified* — **P2**
- `ShieldMonitorService.currentForegroundPackage` and `AppShieldOverlayManager` state are changed from parallel coroutines (`serviceScope.launch` in `onStartCommand` and in the loop). Use a single-thread dispatcher (`Dispatchers.Default.limitedParallelism(1)`) or a `Mutex`.
- `ShieldAccessibilityService.isParentUninstallAllowed` lives in a static field without `@Volatile` on `parentUninstallAllowedUntil`, and it's lost if the process dies. Acceptable, but add a comment.
- `CurfewLockActivity.onWindowFocusChanged` sends `ACTION_CLOSE_SYSTEM_DIALOGS`, which throws `SecurityException` for `targetSdk ≥ 31`. Remove it. It also relaunches itself when it loses focus to its **own** biometric prompt or PIN dialog. Check that the fingerprint prompt still works.
- `ShieldApplication.onCreate` launches the lock activity from `Application` for every process start (including the accessibility service process start). Fine, but de-duplicate with BUG-7.

### BUG-9 · Manifest declares a class that doesn't exist  — *Verified* — **P2**
- `AndroidManifest.xml:50-53` declares `.ui.onboarding.PermissionSetupActivity`, but no such class exists. Remove the entry, or rename it to the real onboarding host.

---

## 2b. Dashboard & onboarding bugs (`ParentDashboardActivity.kt`, `FirstTimeSetupScreen.kt`)

Line numbers are approximate. All items were verified by reading the code.

| ID | Problem | Fix |
|----|---------|-----|
| UI-1 | Dashboard lock check (~114) and the "left today" ring (~515) ignore `isDailyLimitEnabled`/`unlimitedDays`, the same as BUG-1. | Use the single `evaluateDeviceLock` from BUG-1. |
| UI-2 | **Unblocking an app makes it unlimited.** Blocked apps are saved with `dailyLimitMinutes = 0` (~1683, TikTok seed ~235). The switch (~1249) flips only `isBlocked`, and `0` means no limit in `PolicyEngine`. The slider still shows 15m. | On unblock, set `dailyLimitMinutes = max(current, 15)`. Never store 0 as a "blocked" sentinel. |
| UI-3 | `initDefaultAppLimitsIfEmpty` (~223) re-seeds and auto-blocks TikTok/YouTube whenever the list is empty, so deleting all limits brings them back. It runs on the main thread. | Remove the seeding, or run it once behind a `seeded` flag. |
| UI-4 | Sliders (~1285, ~1845) write prefs and reload JSON on every drag frame. | Use local state and save in `onValueChangeFinished`. |
| UI-5 | Bedtime start can equal end ("0 h locked", never locks). `is24Hour=false` is hard-coded. Footer text "Bedtime lock 9:00 PM – 7:00 AM" (~734) is fixed and ignores the settings. | Validate start ≠ end, follow the system 24 h setting, and render the footer from the policy. |
| UI-6 | Emergency number (~2119, setup ~921) saves on every keystroke, can be empty and defaults to "911". | Validate with `PhoneNumberUtils`, block save when invalid, default by SIM/locale (see BUG-4). |
| UI-7 | Rerun setup resets the daily limit to 120 and the name to "Leo" (setup ~74-75) instead of loading the saved values. | Pre-fill from the repository. |
| UI-8 | "Start Protecting" works with **zero** permissions granted (setup ~390). | Require Usage Access, Accessibility, Overlay and Device Admin, or show a red "Not protected" state (FEAT-3). |
| UI-9 | The recovery key is never shown during onboarding. It can only be revealed with biometric (~2457), so parents without a fingerprint never see it. | SEC-1 step 2: show it in onboarding and reveal it again only with the PIN. |
| UI-10 | Accessibility-enabled check uses `contains(packageName)` (setup ~1346) → false positives. | Parse `ENABLED_ACCESSIBILITY_SERVICES` and compare full `ComponentName`s. |
| UI-11 | Usage numbers refresh only on tab change (~277). `queryDailyUsage()` runs on the main thread in `onResume` and in composition (~101, ~271). | Move to the ViewModel on `Dispatchers.IO`, refreshing every 30 s while visible. |
| UI-12 | "Scan" button does nothing: `onBiometricAuth {}` (~2319). Copy at ~2379 mentions "default PIN". | Wire it up or remove it. Delete the default-PIN copy (SEC-2). |
| UI-13 | "Apps today" shows `take(4)` of an unordered map (~885). | Sort by usage, descending. |
| UI-14 | App icons are rasterised on the main thread during composition, without `remember` (~989, ~1448, ~1536). The list stutters. | Load once on IO into an `LruCache<String, ImageBitmap>`. |
| UI-15 | Bonus pill removes the bonus in one tap (~561). Instant lock has no confirmation. Labels: "New 4-digit PIN" though 4–8 digits are allowed (~3264); "Forgot PIN?" inside Change PIN while signed in (~2809). | Add confirmations and fix the copy. |
| UI-16 | No `rememberSaveable`, so rotation resets the selected tab and the onboarding step. | Use `rememberSaveable` or ViewModel state. |

**Design-system / accessibility (P2):**
- 121 hard-coded `Color(0x…)` in the dashboard and 21 in setup, 0 uses of `MaterialTheme.colorScheme`. Brand tokens in `Color.kt` (#2D60FF) don't match the screens (#1E5B3E). The color tokens are redeclared in ~7 composables. Move everything to `MaterialTheme` tokens and choose one brand palette (the landing page should use the same tokens; the owner asked for this).
- Dark mode is forced off (`ShieldChildTheme(darkTheme=false)`, ~140/154). Support the system setting.
- Almost all text is hard-coded in Kotlin (`strings.xml` has 10 entries). Extract it (FEAT-10).
- Bottom nav and day chips: `indication=null`, no `Role.Tab`/`selected` semantics, and TalkBack reads each label twice. Touch targets of 32–38 dp (delete, close, Add App) need to be ≥ 48 dp. Fixed heights (64/56/78/46 dp) clip at large font sizes; use `heightIn(min=)`.
- PIN entry is implemented 5 times (gate, change, uninstall, recovery, setup), and permission rows are copy-pasted 5 times. Extract a `PinField` and a `PermissionRow`.
- Child name: limit to 30 chars, and use `maxLines=1` + ellipsis in the "${name}'s Phone" header.

---

## 3. P1 – Product gaps (what's missing)

| ID | Gap | Why it matters | Suggested scope |
|----|-----|----------------|-----------------|
| FEAT-1 | **Remote approval for "+15 min"** | Today it's in-person PIN/fingerprint or an SMS that only *asks*. The parent has to physically take the phone. | v1: SMS reply code. Show a 6-digit one-time code on the lock screen; the parent replies with an HMAC code derived from the PIN and time, or reads it out over a call. v2: optional parent companion app (pairing by QR, Firebase/ntfy push). |
| FEAT-2 | **Tamper / activity log for the parent** | Silent bypass attempts are invisible. | Local log: time changed, accessibility disabled, overlay revoked, uninstall attempt, recovery key used, wrong PINs. Shown in the Security tab. |
| FEAT-3 | **Health check / degraded-protection banner** | If any permission is revoked (e.g. Android auto-revokes it, or an OEM battery killer stops the service), protection silently stops. | Dashboard status card that is red when any of these is missing: Usage Access, Overlay, Accessibility, Device Admin, battery-optimisation exemption, notifications. "Fix" button for each. |
| FEAT-4 | **Battery-optimisation exemption + OEM guidance** | OnePlus/Xiaomi/Samsung kill foreground services. | Ask for `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in onboarding, and add links per manufacturer (dontkillmyapp.com). |
| FEAT-5 | **Per-day schedules** | Schooldays and weekends need different bedtimes and limits. | `Map<DayOfWeek, DayRule{limit, curfewStart, curfewEnd}>`. Replaces `unlimitedDays`. |
| FEAT-6 | **Always-allowed apps** | Phone, Messages to parents, maps, school apps should still work during the lock. | Allow-list checked in the lock path and covered by the SOS allow-list (BUG-4). |
| FEAT-7 | **Warnings before the lock** | Locking without warning causes fights. | Notification at 10 min and 1 min left (daily and per app), and before bedtime. |
| FEAT-8 | **Weekly usage history** | Parents want trends. | Keep 7–30 days of `{date, total, perApp}` in local storage, shown as a simple bar chart. |
| FEAT-9 | **Backup/export of settings** | Reinstalling loses everything. | Export or import a JSON file protected by the PIN. Never include the PIN or recovery hash. |
| FEAT-10 | **Localisation** | The owner is in Singapore. Text is hard-coded English ("Hi Mom/Dad"). | Move all strings to `strings.xml`. Add `ms`/`id`/`zh` later. |

---

## 4. P2 – Code quality & architecture

### ARCH-1 · Split the 3,578-line `ParentDashboardActivity.kt`
Split it into `ui/dashboard/{OverviewTab, AppLimitsTab, CurfewTab, SecurityTab, components/*}.kt` and add a `DashboardViewModel` (with `StateFlow<UiState>`) that owns all reads and writes to `PreferencesRepository`. No logic inside composables. Do the same for `FirstTimeSetupScreen.kt` (1,358 lines → one file per step).

### ARCH-2 · Storage
- Replace raw `SharedPreferences` + hand-written JSON with **DataStore** (already in `libs.versions.toml` / dependencies but unused) or keep SharedPreferences and remove the unused dependency. Pick one.
- Encrypt secrets (PIN hash, recovery hash) with a **Keystore**-backed key (`androidx.security:security-crypto` or a small AES-GCM helper using `KeyGenParameterSpec`). The prefs file name says "secure", but it isn't.
- Write policy changes with `commit()` (synchronous) where the lock decision depends on them right away, e.g. the instant lock.
- `getChildName()` defaults to `"Leo"`. Default to an empty string and have onboarding require a name.

### ARCH-3 · One source of truth for the rules (TS vs Kotlin)
The Jest tests (100% coverage) test `src/engine/policyRules.ts`, which **doesn't run on the phone**. The README presents them as proof the app works. Choose one:
- **(a) Recommended, smallest:** port the tests to Kotlin JUnit (`app/src/test/...`) against `PolicyEngine`, `UsageMonitor` (with fake events), `ParentAuthManager` (with an in-memory repo). Then delete `src/`, `tests/`, `package.json`, `babel.config.js`, `tsconfig.json` and `coverage/`.
- **(b)** If the owner wants to bring React Native back for the UI (they are a JS developer and didn't ask for it to be removed): restore an RN/Expo layer for the parent dashboard, keep enforcement native, and expose `ShieldBridgeModule` as a real TurboModule. In that case the TS rules may stay, but **only for UI previews**. Enforcement decisions must stay in Kotlin.
- **Ask the owner** which one before doing either. Until then, delete only `bridge/ShieldBridgeModule.kt` if it's (a); keep it if it's (b).

### ARCH-4 · Error handling
There are 20+ empty `catch (_: Exception) {}` blocks. Each one must at least `Log.w(TAG, …)`, and enforcement failures must be added to the tamper/health log (FEAT-2/3). An enforcement app that fails silently looks like it's working when it isn't.

### ARCH-5 · Build & release hygiene
- `isMinifyEnabled = false` for release. Turn on R8 with resource shrinking, and check that the keep rules cover the accessibility service, admin receiver and boot receiver (the `service.**` rule already does).
- `versionCode = 1` is hard-coded. Bump it per release; derive from a git tag in CI.
- `buildToolsVersion = "35.0.0"` with `compileSdk 36` – drop `buildToolsVersion`.
- `biometric = 1.2.0-alpha05` – use a stable version (`1.1.0`) unless an alpha-only API is needed.
- AGP 8.6 / Kotlin 2.0.0 / Compose BOM 2024.09 are old for `targetSdk 36` – update together.
- Add **GitHub Actions** CI: `./gradlew lint testDebugUnitTest assembleRelease` (signing from secrets), and upload the APK to Releases on tag (the owner's other repo `wird` already has this pattern – copy it).
- Remove `QUERY_ALL_PACKAGES`. Launcher apps can be listed with a `<queries><intent>MAIN/LAUNCHER</intent></queries>` block, and the Play Store restricts the permission.
- The device admin policy XML asks for `wipe-data`, `expire-password`, `encrypted-storage`, `disable-camera` and more, but only uninstall protection is used. Trim it to the minimum (`force-lock` at most). The activation screen currently tells parents the app can **erase the phone**, which is frightening and untrue.
- Remove `REORDER_TASKS` if it's no longer needed after BUG-8. Remove `SCHEDULE_EXACT_ALARM`, or use it (BUG-7).

---

## 5. P1 – Docs, honesty & distribution

### DOC-1 · README / landing page claims that are false today
| Claim | Reality | Fix |
|---|---|---|
| "Parental controls kids cannot outsmart", "zero-bypass", "close every known loophole" | Clock change, clear storage (via launcher), safe mode, and default recovery key all bypass it. | Tone down. Add a "What it protects / what it can't" table. Recommend Device Owner mode for the strongest protection. |
| Recovery key example `SHLD-9A4F-K82X-7M1P` | That *is* the real key on every phone (SEC-1). | Remove it. |
| "Exponential progressive lockouts (30 s → 5 m)" | Fixed: 5 wrong tries → 15 min. | Match the code, or implement the progression. |
| "Recovery keys … hashed with their own unique salts" | Stored in plain text. | Fix SEC-1, then the claim is true. |
| "Policy rules … verified with Jest" | Tests a TS copy, not the Kotlin that runs. | ARCH-3. |
| "Emergency SOS … 911" | Wrong default for the owner's region. Same number is used for parent SMS. | BUG-4. |
| "3-step Permission Setup wizard" | Check that it matches the current onboarding. | Re-capture screenshots after fixes (the owner already complained twice about outdated screenshots). |

### DOC-2 · Re-issue the release
After the P0 fixes, publish **v1.0.1** with a short security notice: "Update now. A shared default recovery key allowed a PIN reset; after updating, generate a new recovery key in Security." Consider marking v1.0.0 as superseded in its release notes.

### DOC-3 · Privacy policy
`docs/privacy.html` must mention the Accessibility API use (reads window content of Settings/installer screens to detect tampering), Usage Access, and that nothing leaves the device. Google Play requires a prominent in-app disclosure + consent screen **before** the user is sent to enable Accessibility, and a Play Console declaration. Add that disclosure screen to onboarding even if you're only distributing on GitHub.

---

## 6. Tests to add

- **TEST-1 (Kotlin unit, JUnit + fake Calendar):** `PolicyEngine` – every LockReason, priority order, overnight curfew at the edges (20:59, 21:00, 06:59, 07:00), unlimited day, disabled limit, override active, bonus minutes.
- **TEST-2 (Kotlin unit):** `ParentAuthManager` – no default PIN, lockout after 5 tries, lockout expiry, recovery-key success/fail/lockout, PBKDF2 migration. `UsageMonitor` aggregation with a fake event list (needs an `EventSource` interface around `UsageStatsManager`).
- **TEST-3 (manual device checklist, `docs/TEST_MATRIX.md`):** for each phone (OnePlus 6 + one other), with and without Device Owner, record pass/fail for: reboot during curfew, Home/Recents during lock, notification shade during lock, clock change, time-zone change, clear storage, uninstall from launcher, uninstall from Settings, disable accessibility, revoke overlay, safe mode, SOS call, +15 min by PIN, +15 min by fingerprint, recovery-key reset, single-app limit, battery % per day.
- Add an instrumented smoke test (Compose UI test) for onboarding: PIN set → recovery key shown → name required.

---

## 7. Suggested execution order

1. SEC-1, SEC-2, SEC-6, SEC-8, SEC-9, UI-2 (small, urgent) → **v1.0.1**.
2. BUG-1 + UI-1, BUG-3, BUG-4 + UI-6, BUG-6, SEC-10 + TEST-1/TEST-2.
3. SEC-3, SEC-4, SEC-5, SEC-7, BUG-2, BUG-5, BUG-7, BUG-8, BUG-9, UI-3…UI-16.
4. FEAT-3 (health banner), FEAT-4, FEAT-7, FEAT-2.
5. ARCH-1…5, DOC-1, DOC-3, CI.
6. FEAT-1, FEAT-5, FEAT-6, FEAT-8…10.
7. TEST-3 on two devices → screenshots → README → **v1.1.0**.

## 8. Ground rules for the executing agent

- Never weaken enforcement to make something easier to test. Use a debug-only build flag instead.
- Every task that touches lock decisions must add or extend a Kotlin unit test.
- Never print, log or commit a PIN, recovery key, keystore password or `.jks`.
- Don't add a network dependency or account system without the owner's OK (the "100% on-device, no account" promise is part of the product).
- Ask the owner before ARCH-3 (React Native decision) and before changing the signing key.
