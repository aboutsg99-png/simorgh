# Simorgh: handoff

Android VPN client (Kotlin, Jetpack Compose), Persian / RTL UI with Vazirmatn UI FD.
A modified fork of an AGPL-3.0 project (see `NOTICE.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`).
This file is enough to continue from the repository alone. No secrets are kept here.

## Build and CI

- Builds only run in GitHub Actions: `.github/workflows/build.yml`, on push to `main`
  (tags `v*` also publish a GitHub Release; do not tag without the owner's go-ahead).
- The workflow builds `libfoxybox.so` (Vauth/sing-box `v1.0.0-masque`, pinned commit) with Go,
  then `./gradlew :app:assembleRelease`, signs with the release key from repository secrets,
  uploads the artifact `Simorgh-apk` and force-pushes the APK to branch `apk-builds`.
- Output: `Simorgh-<versionName>-arm64-v8a.apk` (arm64-v8a only; Rubah needs the arm64 binary).
- Release builds use R8 (full mode is the AGP 8 default) with resource shrinking.
  `app/src/main/baseline-prof.txt` is a hand-written baseline profile installed by
  `androidx.profileinstaller`.
- Local compile check without an x86 machine: an aarch64 JDK 17, Android platform 35, and an
  aarch64 `aapt2` passed through `android.aapt2FromMavenOverride` are enough for
  `:app:compileDebugKotlin` (native code is prebuilt in `app/src/main/jniLibs`).
- Do not change: signing secrets/keystore, the update checker (`update/`), the Psiphon SponsorId.

## Layout

- `applicationId` `app.simorgh.vpn`; Kotlin namespace stays `com.uacspoofer.mobile` because the
  prebuilt JNI libraries bind to it.
- `engine/` engines, `vpn/UacVpnService.kt` the service, `core/` connection state machine and
  `VpnController`, `profiles/` configs and subscriptions, `settings/` stores.
- `simorgh/` (new in 1.0.2) app-level state:
  - `LocationState`: single source for engine + country (selection, running engine, verified
    exit). Home card, map, notification and widget all read it.
  - `ActiveEngineStore`: the engine the service is really running (published by the service).
  - `ExitIpChecker`: exit IP/country through the tunnel SOCKS (DB-IP free API + Cloudflare
    trace; DB-IP wins, Cloudflare fills in). Direct check when disconnected = the user's own
    country (the app is excluded from the VPN, so direct requests never enter the tunnel).
  - `auto/AutoEngineStore`, `auto/AutoConnector`: Auto engine (ordered list, include toggles,
    per-network memory of the engine that last connected), pure orchestration over the normal
    connect path. `VpnController.start` routes to Auto when it is on.
  - `SimorghRuntime`: started from `UacApplication` (main process only); protected-time
    sessions, exit re-checks, engine results, recent places, auto-reconnect.
  - `PowerMode`: the three battery modes mapped onto the existing battery settings.
  - `speed/SpeedTest.kt`: Ookla and Shatel (LibreSpeed) speed test + history.
  - `servers/FastestRepository`: the unified «پرسرعت‌ها» list (configs, subscriptions, Rubah
    countries after sign-in, live tunnel row), real measurements only.
  - `SelectionActions`: apply a picked engine + country/config and connect or switch.
- `ui/shell/` (new in 1.0.2) the app shell: `ShellRoot` (5-tab floating nav), `HomeTab`,
  `PowerButton`, `WorldMap` (Natural Earth 1:50m asset), `ServersTab`, `SpeedTab`,
  `EnginesScreen`, `PersonalizeScreen`, `ProfileTab`, `GuideTab`, `ShellTheme`, `ShellPrefs`.
  Existing pages (settings, engine settings, configs, logs, support...) open full screen through
  the old `MainScreen` with `startDestination` and return to the shell on back.
- Map asset: `app/src/main/assets/map/world50.bin`, built by `scripts/gen_world_map.py` from
  Natural Earth `ne_50m_admin_0_countries` GeoJSON (public domain).

## Engines (display names)

Parvaz (Xray, configs/subscriptions), Sayeh (Tor + WebTunnel), Shahpar (PoW: Nur = WARP,
Abr = Psiphon, Pol = WebTunnel), Tir (Direct), Rubah (Firefox VPN with the user's own Firefox
account, MASQUE via sing-box), Auto (orchestrates the others).

## Design references

`docs/mockups/` holds the approved mockups (home, servers, speed test, personalize, and the
round-2 connect states, map card, fastest list and engines page).

## Status of 1.0.2

See the bottom of this file (kept current by whoever works on it).

<!-- STATUS -->
- Work in progress; see the latest commit message and CI run.
