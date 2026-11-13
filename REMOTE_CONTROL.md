# Manjaniq remote control

This is an isolated startup-policy component for the Android client. It does not
modify RSTA/Aether protocols, native libraries, proxy/tunnel arguments, profiles,
engine configuration storage, or the existing navigation/screens.

## Integration boundary

- `ManjaniqApplication` is the process-scoped composition root. It creates the
  remote-control session lazily; application creation itself performs no network
  work and does not initialize an engine.
- `MainActivity` wraps the unchanged `ManjaniqApp()` in `RemoteControlGate`.
  The existing app is not composed until the gate allows access. Rotation and
  language-triggered activity recreation reuse the same in-memory session.
- `EngineService.onStartCommand` and `AetherService.onStartCommand` consult a
  nonblocking, authorization-only guard before their existing start handlers.
  Stop actions are always allowed. Aether's null-action sticky restart is covered
  too; a rejected sticky restart is not automatically retried.
- On a confirmed ban, mandatory update, or unverified password requirement, the
  composition root requests Stop through the existing public engine APIs for
  engines believed to be active. It never calls native start/stop directly.

New implementation lives in `com.manjaniq.remotecontrol`:

| File | Responsibility |
| --- | --- |
| `ManjaniqRemoteControl.kt` | Bounded HTTPS GET, response limits, cleanup, fail-open transport result |
| `RemotePolicy.kt` | Strict JSON contract parsing, separate from engine config |
| `RemoteControlSession.kt` | Startup deadline, gate precedence, password verification, process-local state |
| `RemoteControlGate.kt` | Compose blocking/notice screens; no changes to existing screens |
| `RemoteControlServiceGuard.kt` | Read-only service-start authorization boundary |

## Request and response

```http
GET https://manjaniq-hub.manjaniqhub.workers.dev/api/check?device_id={encoded_ANDROID_ID}
Accept: application/json
Cache-Control: no-cache
```

The identifier is read with:

```kotlin
Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
```

It is URL-encoded and needs no additional permission. `ANDROID_ID` is scoped to
the app signing key/user/device and can change after uninstall/reinstall or a
factory reset; it is not a permanent hardware identifier.

```json
{
  "is_banned": false,
  "force_update": false,
  "latest_version_code": 2,
  "app_password": "",
  "announcement": ""
}
```

Missing/null optional fields mean no restriction. Booleans must be JSON booleans;
password/announcement must be strings. Version codes must be nonnegative integers
(JSON integers or digit-only strings are accepted). A forced update without a
valid latest version is treated as an invalid policy, not a guessed restriction.
The installed code is read with `PackageManager.getPackageInfo` and
`PackageInfoCompat.getLongVersionCode`.

Gate precedence:

1. **Ban:** persistent, non-dismissible restriction screen; no main UI or engine
   start access. “Check again” re-polls but cannot dismiss the restriction.
2. **Mandatory update:** blocks only when `force_update` is true and installed
   code is strictly lower than `latest_version_code`. Equal/newer clients pass.
   The update button opens this repository's fixed GitHub Releases page, matching
   the project's APK distribution; the remote JSON cannot supply an arbitrary URL.
3. **Password:** required when `app_password` is nonempty. Comparison is exact
   (including whitespace), over UTF-8 bytes, using SHA-256 digests and
   `MessageDigest.isEqual`. Wrong attempts stay blocked. A verified password is
   remembered only for the current process and invalidated if the policy password
   changes or a later check fails open.
4. **Announcement:** shown after the password gate when nonblank; acknowledge to
   continue. It is not repeated on ordinary activity recreation.
5. **Allowed:** compose the unchanged app and permit normal engine starts.

## Resilience and security properties

- HTTPS only, with Android's default certificate and hostname verification.
- No redirects, HTTP downgrade, shared proxy interceptor, cookie store, or cache.
- Connect timeout: 3 seconds; read timeout: 3 seconds; whole startup wait:
  7 seconds. The deadline watcher disconnects the active socket. A platform DNS
  call that ignores interruption may finish in the background, but cannot hold
  the UI past the deadline.
- Only HTTP 200 is parsed. Other statuses, timeouts, DNS/socket/TLS errors,
  invalid JSON/field types, missing local identity/version, and oversized bodies
  (more than 64 KiB) **fail open**.
- No restriction or password is persisted. A fresh OS process starts with a new
  check; an unavailable backend cannot resurrect an old cached ban.
- No device ID, password, response body, or query URL is logged. The password is
  not placed in Compose saved state, Intents, preferences, or engine logs.
- A backend outage does not stop an already running local engine. Confirmed
  restrictions do request Stop and reject new starts until the gate permits access.
- The check runs once per OS process, not on every Activity resume. Blocking
  screens offer an explicit recheck; background policy push/periodic enforcement
  is outside this startup integration.

### Important limitation

The requested `app_password` is a client-side shared gate, not strong
authentication. The server sends it to the client, and the mandated offline
fallback intentionally allows access without a verified policy. A modified client
or an offline launch can bypass client-side gates. Do not treat this as protection
for privileged server operations or confidential data. Those require server-side
authentication/authorization and a separately designed offline policy. Constant-
time comparison avoids a local string-comparison timing shortcut; it does not
change this trust boundary. The Worker should also minimize retention/access logs
containing the query-string device identifier.

## Verification

Host tests cover the JSON contract, HTTPS request settings/encoding, non-200 and
exception fallback, body limits, startup timeout, gate precedence, password
rotation, announcement acknowledgement, and real Compose gate rendering.

Instrumentation uses `ManjaniqTestRunner` to supply a test-only application through
the same `RemoteControlHost` composition interface. There is no production
test-mode flag, bypass URL, or alternate UI. Existing launcher/navigation/locale
and native-engine tests still exercise the real Activity/services/engines without
depending on a live operator's policy. A real service-start rejection test covers
the RSTA boundary.

The emulator's phase-1 manual artifact launch disables its network radios before
launch and restores them afterward, explicitly verifying production fail-open
behavior. Phase 2 uses the deterministic instrumentation composition root.

Run the existing pipeline unchanged:

```sh
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest
./gradlew assembleDebug
./gradlew connectedDebugAndroidTest
```

The native libraries are built by the existing GitHub Actions workflow before
APK/emulator verification. No new production networking dependency is required.
