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

The UI shows real names only: Xray (configs/subscriptions), Tor + WebTunnel, Cloudflare WARP /
Psiphon / Psiphon + WARP (the PoW engine by exit mode), Direct, Firefox VPN (user's own Firefox
account, MASQUE via sing-box), WireGuard (own configs, Proton VPN, Windscribe), Auto. The old
codenames (Parvaz, Sayeh, Shahpar, Nur, Abr, Pol, Tir, Rubah) survive only in code identifiers,
comments and engine-emitted status keys matched by PowStatusCopy/HomeComponents.

## Design references

`docs/mockups/` holds the approved mockups (home, servers, speed test, personalize, and the
round-2 connect states, map card, fastest list and engines page).

## Status of 1.0.2

See the bottom of this file (kept current by whoever works on it).

<!-- STATUS -->
- 2026-11-24: 1.0.2 (versionName 1.0.2 / versionCode 3) committed to main: 82761ff (code) + fe659e1 (docs). Main run 45287990715 green on first try; artifact Simorgh-apk (release-signed, arm64-v8a, includes libfoxybox.so).
- Follow-up commit: Test tasks set `roborazzi.test.record=true` so the CI `shots` job actually writes app/build/shots/*.png (first run passed but uploaded nothing). Run 45289105481 then failed in `shots` because Robolectric could not download android-all-instrumented (IOException) mid-test; the workflow now curls `android-all-instrumented-14-robolectric-10818077-i7.jar` (with retries) into ~/.m2/repository, where Robolectric's MavenArtifactFetcher finds it without a network fetch (`robolectric.offline` + a dependency dir was tried in 7304cc0 and failed with "Unable to locate dependency"). Test tasks log stdout/stderr and full exceptions. The `shots` job is continue-on-error and never blocks the APK.
- Green: 905c197, main run 45291329648 (build + shots both green). APK Simorgh-1.0.2-arm64-v8a.apk 86,603,328 bytes, signer SHA-256 98:4C:1B:C8:…:ED:C8:3D:32 (CN=Simorgh), v2+v3 signed, contains lib/arm64-v8a/libfoxybox.so; versionName 1.0.2 / versionCode 3. Artifact shell-shots = 9 Robolectric PNGs (1200x4800).
- No tag or GitHub Release for 1.0.2 yet (CI publishes the APK to the apk-builds branch on main pushes).
- Not device-tested: new shell UI, world map, fastest servers, speed test (Ookla/Shatel), auto engine, power modes, notification/country-label fixes.

### 2026-11-27: `integration` branch (local only, not pushed)
- Worktree /workspace/work/simorgh-int, branch `integration` from main e324db1:
  merge perf-ui (a262ba8, 83e1c73) -> merge engines (4ee7579; Outline WIP left out, it was
  uncommitted) -> merge speed (8003cf4, b047daf, 97fb064) plus fix commits.
- Conflicts: EnginesScreen.kt (perf-ui's EngineChoice list + Auto chain card kept, engines'
  EngineToolsCard added, WireGuard row + settings route), ShellFormat.kt (real names via
  engineDisplayName, WireGuard icon/description), FastestRepository.kt (real-name tag + speed's
  cached Firefox VPN pings). SelectionActions, AutoConnector, UacVpnService, MainScreen,
  EngineCatalog and AppDrawer merged cleanly.
- Green locally: :app:compileDebugKotlin and full :app:testDebugUnitTest, 548 tests, 0 failures.
  The 6 earlier failures were all pre-existing at e324db1 (stale tests or the TorStatusCopy
  key bug) and are fixed: PowEngineIsolationTest, AiCleanHopTest, TorRcWriterTest,
  TorStatusCopyTest x2, ConnectionStateMachineTest.
- Bug sweep fixes: Game DNS FGS-timeout crash; Auto replacing Game DNS; WireGuard select crash on
  unreadable Keystore file; stuck busy flags (Game DNS race, WG ping test, Proton sync); RTL
  back/forward arrows inverted in the shell; stale map raster after a skin switch; per-second
  home recomposition while disconnected; codenames on every legacy page, the notification and
  the home widget; TorDaemon mojibake.
- Still open (see the integration report): Game DNS VpnService intent-filter competes with the
  main service for always-on VPN; WgTunnelStore does Keystore file I/O on the main thread;
  home shows "not protected" while Game DNS is on; ProtonVpn.signedIn() read during
  composition; device testing of everything above.
