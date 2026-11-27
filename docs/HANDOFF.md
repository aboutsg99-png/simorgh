# Blackdey (بلک دی): handoff

Android VPN client (Kotlin, Jetpack Compose), Persian / RTL UI with Vazirmatn UI FD.
A modified fork of an AGPL-3.0 project (see `NOTICE.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`).
This file is enough to continue from the repository alone. No secrets are kept here.

## Build and CI

- Builds only run in GitHub Actions: `.github/workflows/build.yml`, on push to `main`
  (tags `v*` also publish a GitHub Release; do not tag without the owner's go-ahead).
- The workflow builds `libfoxybox.so` (Vauth/sing-box `v1.0.0-masque`, pinned commit) with Go,
  then `./gradlew :app:assembleRelease`, signs with the release key from repository secrets,
  uploads the artifact `Blackdey-apk` and force-pushes the APK to branch `apk-builds`.
- Output: `Blackdey-<versionName>-arm64-v8a.apk` (arm64-v8a only; Rubah needs the arm64 binary).
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
  Every other page opens full screen on the shell's own back stack (`ShellNav.kt`, see
  "Shell navigation" below); there is no `MainScreen`, drawer or second dashboard.
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

### 2026-11-27: `v2-net` branch (connection fixes, from integration e5d1d70)
- MTU: TUNs terminated by hev (Tor, Firefox VPN) are back to the app TUN MTU (1280 default; Tor's own
  choice wins) instead of 1500; WireGuard uses the tunnel MTU clamped to 1280..1420. 1500 dropped
  full-size UDP/QUIC inside MASQUE/WireGuard on mobile paths.
- Auto: per-engine budgets raised (Firefox VPN 150 s, Psiphon 120 s, WireGuard 120 s, Tor 150 s,
  Xray 75 s, Direct 60 s); configs that missed the Xray race get 40 s (was 25 s) and that early cut no
  longer opens the Flux breaker; every config that answered the race (up to 4) is tried. Firefox VPN
  no longer falls back to Xray/Psiphon inside an Auto attempt.
- Firefox VPN (MASQUE kept as is, HTTP/1.1, HTTP/2, HTTP/3): edge ping parallelism back to 6; two
  TLS-reachable edges plus one TLS-reset edge (HTTP/3 first there), see `FoxyPathPlan`.
- Firefox VPN over Mozilla VPN WireGuard (`FoxyTransport`): needs the user's Mozilla VPN subscription,
  separate sign-in on vpn.mozilla.org (PKCE, `MozillaVpnAccount`, token + key in a Keystore file).
  Modes: WireGuard UDP (51820, 53, random listened port), WireGuard over TCP (Mullvad UDP-over-TCP
  framing on 443/80, `Udp2TcpRelay`), WireGuard inside Shadowsocks (Xray dialerProxy chain,
  aes-256-gcm/"mullvad", 51900-51949), Auto (all three, then MASQUE). HTTPS stays the fallback of
  every mode. Auto uses the WireGuard modes first when a Mozilla VPN sign-in exists. After a WireGuard
  connect the running engine is WireGuard (session handled by the WireGuard coordinator); recovery
  goes back through Firefox VPN. Not device-tested; Shadowsocks target/port and Guardian login flow
  are from the public Mozilla VPN / Mullvad clients, unverified here.
- WireGuard: endpoint host names are resolved by the phone before Xray starts (Xray resolved them
  through its own tunnel-routed DNS: hostname endpoints such as WARP/Windscribe never came up);
  handshake check is three 7 s rounds.
- Configs: share links without `type` are TCP (ws only when a path/host is given), `type=raw` = tcp.
  Subscriptions: remark-based usage/expiry (`SubscriptionRemarkInfo`, Persian/English, Jalali dates),
  `profile-update-interval`, last refresh error, `refreshAll`, `deleteMany`, `addPrivate`; effective
  used/total/remaining/expiry on `Subscription` (nullable). Location picker lists the user's configs
  before Mozilla's countries (Mozilla capped at 12 there).
- Game DNS: an always-on start that resolves to its service hands over to the main tunnel; the main
  service's VpnService filter has priority 100.
- Firefox VPN anti-filter (HTTPS / MASQUE path; HTTP/1.1, HTTP/2, HTTP/3 modes unchanged):
  `AntiFilterMethod` (Auto, Plain TLS, TCP fragment, TLS record fragment, Clean IP) in
  `FoxyEngineSettings.antiFilter`, chips in `FoxySettingsPanel`. `FoxyBoxConfig.build(antiFilter=)`
  emits the fork's `tls.fragment` + `fragment_fallback_delay` 500ms or `tls.record_fragment` (uTLS
  stays on; HTTP/3 gets neither). Clean IP: `FoxyCleanEdgeScanner` probes the edge block
  (23.235.42.0/24 seed + the /24 of every resolved edge) on the edge's own port with the real SNI and
  a hostname-verified certificate; the hit becomes `edgeAddress` (sing-box dials the IP, SNI/Host
  stay the server name). Every address of 23.235.42.0/24 served `*.m1.fastly-masque.net` on 2499 on
  2026-11-27; 443 there is Fastly's default cert, 8443 closed, so the scanner does not use 443/8443.
  `AntiFilterPlan` (pure): order by the ping signal (TLS ok -> plain, fragment, clean IP, record;
  TLS reset -> fragment, record, plain (for HTTP/3), clean IP; nothing answered -> clean IP only),
  the method that last worked on this network first, per-method timeouts 45/30/35/25 s cut to the
  Auto attempt's remaining time (`AutoConnector.remainingMs()`); a method the user picks runs alone.
  `FoxyAntiFilterStore` keeps clean IPs (24 h) and the last working method per network key.
  Auto budget for Firefox VPN raised 150 -> 180 s.
- Not done: ECH (the fork supports it with uTLS, but `*.m1.fastly-masque.net` has no HTTPS/ECH DNS
  record, so it can only fail); chain via WARP (needs the PoW core running in proxy mode beside
  Firefox VPN in one service: shared singletons `PowSocksConnectOnly`/`PowLiveExit`/watchdog, the
  service runs one engine at a time, and WARP's SOCKS is TCP-only so HTTP/3 could not ride it).
- Settings: Mozilla VPN WireGuard route chips are hidden unless `MozillaVpnAccount` is signed in.
- Fix: switching between Cloudflare WARP / Psiphon / Psiphon + WARP while connected was allowed
  (same engine mode) and only changed the saved exit; now blocked like any engine change.
- Verified here: compile + JVM tests; the four TLS shapes pass `sing-box check` (fork built with
  with_quic,with_utls) and reach the real edge from a non-Iranian network (TLS completes, proxy
  answers 400 without a valid pass). Not verified: whether fragmenting/clean IP beats the Iranian
  filter, on-device behaviour, TCP_INFO-based fragment ACK wait on Android.

### 2026-11-27: `v2-ui` branch (UI overhaul + brand), worktree /workspace/work/simorgh-ui
- Brand: user-visible name is **Blackdey** (Persian «بلک دی») everywhere: app label (`values` /
  `values-fa` `app_name`), notifications, widget, share/clipboard labels, guide, about, README.
  `applicationId app.simorgh.vpn`, the Kotlin package `com.uacspoofer.mobile` and shared-pref /
  keystore names stay unchanged so updates and signing keep working. `update/` untouched.
- Logo (approved): red tulip + white arrow on black. Files are listed in `res/values/brand.xml`:
  legacy/round mipmaps, adaptive `mipmap-anydpi-v26` (black background, foreground, monochrome),
  notification `drawable-*/ic_stat_uac.png` + `ic_stat_vpn.png`, in-app `brand_mark.png`
  (`@drawable/brand_logo`), `brand_wordmark.png` (About), start screen `brand_splash`
  (pre-31) and `windowSplashScreen*` (v31). Brand red `@color/brand_accent` #B3001B; the UI accent
  stays the shell palette's.
- Shell navigation: 5 bottom tabs (VPN, engines, speed test, guide, profile) in `ShellRoot.kt`;
  every other screen is a `Route` on a back stack (`ShellNav.kt` `RouteHost`). Routes:
  LOCATIONS, SERVERS, PERSONALIZE, SETTINGS, AUTO_SETTINGS, XRAY_SETTINGS, POW_SETTINGS,
  TOR_SETTINGS, DIRECT_SETTINGS, FIREFOX, WIREGUARD, PROTON_SIGN_IN, TOR_COUNTRIES, POW_COUNTRIES,
  FLUX, CLEAN_IP, GAME_DNS, CONFIGS, SHARE, APP_BYPASS, SNI_MAKER, ROUTE_TEST, LIVE_LOGS, BATTERY,
  DIAGNOSTICS, SUPPORT, REPORT. The engine gear opens `settingsRouteFor(engine)`.
- Page kit: `ui/shell/ShellKit.kt` (SubPage, PageHeader, SGroup, SRow, SToggleRow, SButton, SNote,
  SField, SSegmented, SRadioRow, STag), `ShellDialogs.kt` (SDialog, SConfirmDialog, update dialog).
  Rebuilt on it: ConfigsPage (configs + subscriptions with used/total/remaining/expiry, update one /
  all, multi-select with select all / copy / delete, add from clipboard/file/text, edit),
  SplitTunnelPage, LiveLogPage, Support/Report/Share/Battery.
- Flat theme bridge: `SimorghShellTheme` writes the shell colours into `UacThemeState.override`
  (`shellPalette`), so every older page reading `UacColors.*` draws in the shell palette. The older
  engine/tool pages keep their bodies but their shared chrome is drawn with the page kit:
  `ToolPageChrome.kt` (scaffold, header) and the `Pow*` rows (`PowSettingsPanel.kt`: card, switch,
  picker, pill, hint, button) delegate to ShellKit; `RouteHost` lays them out right-to-left.
- Removed (dead or replaced): SupportScreen, HomeMetricDialogs, ConfigsScreen, AppBypassScreen,
  LiveLogsScreen, AnimatedDottedWave, BatteryManagementScreen, ConnectProgressOverlay,
  ConnectRescueOverlay, ConnectionAwareFeatureCard, NeonParticles, NeonTuner.
- Merged v2-net up to 541b3cd. JVM tests: 654, 0 failures. Screenshots: `PageShots`
  (`-PshotsDir=...`), sample data seeded through `FastestRepository.setForPreview` /
  `LocationState.setForPreview` (test-only hooks).
- Still to do: SNI builder, route test, diagnostics and the Tor/WARP/Firefox country pages keep
  their own inner layouts on the shared chrome (not rewritten row by row); device testing.
