# WG Tunnel X — development plan

**English** · [Русский](PLAN.ru.md)

This document covers what the fork does and why, how it's built, where its weak spots are and what comes next. State as of October 25, 2026 (release 5.8.2-x.2); based on WG Tunnel 5.8.2 and Xray-core 26.10.21.

## 1. Goal and scope

**Goal.** Give WG Tunnel split routing by domain with the same flexibility as Xray and the clients built on it (v2rayNG, v2RayTun): rules by `geosite`/`geoip`, domain, IP, port, protocol and app, with a decision for every connection and no Android routing table involved.

**Hard requirements.**

1. Every WG Tunnel feature stays, AmneziaWG included.
2. The fork has to release a new version on its own right after each stable WG Tunnel release. That means changes to upstream files must be minimal and predictable, otherwise every merge would need a human.
3. The name is WG Tunnel X, the repository is `wgtunnelx`, and the icon is a stylized X drawn in the style of the original W.

**Out of scope for the first version:** Xray's own protocols (VLESS, Reality, etc.) as outbounds, balancers, and routing in the "Local proxy" and "Lockdown" modes.

## 2. Chosen architecture

### 2.1. Options considered

| Option | Pros | Why rejected / accepted |
| --- | --- | --- |
| A. Xray's built-in WireGuard outbound | One engine | No AmneziaWG; WG Tunnel's recovery, DNS bootstrap and statistics are lost. **Rejected** |
| B. Fork `wgtunnel/core` (the Go backend) and route inside it | One process, one TUN | A second fork with a heavy Go/NDK build and conflicts on every core update. **Rejected** |
| C. sing-box instead of Xray | Built-in WireGuard, rule-sets | The requirement is Xray's behavior and databases; sing-box has no AmneziaWG. **Rejected** |
| D. WG Tunnel backend in userspace mode + Xray-core owning the VPN | Backend untouched, AmneziaWG works, rules match Xray 1:1 | **Accepted** |

### 2.2. Traffic flow (option D)

```
Apps ─► TUN (XrayVpnService) ─► Xray-core (tun inbound, sniffing http/tls/quic)
                                   │  routing: user rules
                                   ├─ proxy  ─► SOCKS5 127.0.0.1:<random port>, random username/password
                                   │             └─► WG Tunnel backend in Proxy mode (AmneziaWG + gVisor netstack) ─► server
                                   ├─ direct ─► the app's own sockets; the app is excluded from its own VPN,
                                   │             so the traffic goes out to the regular network with no loops
                                   ├─ block  ─► blackhole
                                   └─ dns-out ─► Xray DNS module: "tunnel" domains via the tunnel's DNS,
                                                "direct" ones via the system resolver
```

Key decisions:

- **Xray reads the TUN itself.** Xray-core has a `tun` inbound; on Android it gets the file descriptor through `xray.tun.fd` ([proxy/tun](https://github.com/XTLS/Xray-core/tree/main/proxy/tun)). The [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) library passes it to `CoreController.startLoop(config, tunFd)`. No tun2socks layer in between.
- **Xray runs in a separate `:xray` process.** AmneziaWG (`libam-go.so`) and Xray (`libgojni.so`) are written in Go, and each library carries its own Go runtime. Two runtimes in one process share the signal handlers: one runtime takes a fault in the other's code for its own, after which Xray crashes with `fatal error: unknown caller pc` and the process hangs with the VPN up and no traffic. This happened in version `5.8.2-x.1` on a user's phone. The Go developers don't support this setup ([golang/go#22192](https://golang.org/issue/22192), [golang/go#18976](https://golang.org/issue/18976)). So `XrayVpnService` is declared with `android:process=":xray"`, and the app controls it through a `Messenger` (`XrayEngine` ↔ `XrayVpnService`, protocol in `XrayIpc`). If the Xray process dies, the app stops the tunnel, and auto-tunnel brings it back up if needed. If the app process dies, `:xray` closes the VPN by itself. If Xray doesn't confirm a stop within 5 seconds, its process is killed, so the stop always takes effect.
- **The app excludes itself from the VPN** (`addDisallowedApplication`). The WireGuard UDP socket and Xray's `direct` sockets go to the physical network without `protect()`, so routing loops can't happen.
- **The WG Tunnel backend is unchanged.** `BackendMode.Proxy.Standard` already exists upstream (it's what "Local proxy" uses), and its SOCKS5 server supports UDP ASSOCIATE (go-socks5 in [wireproxy-awg](https://github.com/wgtunnel/wireproxy-awg)).
- **Swapping in through DI.** `RoutingTunnelProvider` is a `TunnelProvider` decorator declared in the fork's Koin module, which loads after the upstream modules and overrides the original definition. It intercepts only VPN-mode starts for tunnels that routing applies to. All other calls go to upstream unchanged.
- **Settings live outside Room.** The DB schema and migration numbers belong to upstream; a fork-specific migration would clash with every future one. The rules are stored in `files/xray/routing.json`.
- **Geo databases aren't in the APK.** The runetfreedom geosite file is ~74 MB. The databases are downloaded when routing is turned on, verified (by parsing the category list) and replaced atomically. Xray reads them as a stream, so file size barely affects memory.

### 2.3. Modules and files

| Where | What |
| --- | --- |
| `xray/` (new module) | Rule models and presets, Xray config generator, rule validator against the geo databases, import/export (v2rayNG, Xray), geo database download and WorkManager updates, `XrayVpnService` (`:xray` process), `XrayEngine` and `XrayIpc` (inter-process link), `XrayProcess` |
| `app/src/main/java/.../xray/` | `TunnelProvider` decorator, `AutoTunnelWatchdog`, Koin module, settings screen (Compose, separate Activity) |
| `app/src/x/` | Fork resources and manifest overlay: icons, banner, en/ru strings, Activity |
| `app/wgtunnelx.gradle` + `wgtunnelx.properties` | applicationId, name, version, FileProvider authority, update source, wiring of `:xray` |
| `scripts/`, `.github/` | Upstream merging, hooks, config validation, release publishing |

### 2.4. Hook points in upstream

Every change to WG Tunnel files is listed in `scripts/apply-hooks.py`. There are six of them, about 20 lines in total:

1. `app/build.gradle.kts` — `apply(from = "wgtunnelx.gradle")` at the end of the file;
2. `settings.gradle.kts` — `include(":xray")`;
3. `WireGuardAutoTunnel.kt` — `xrayModule` in the Koin module list, plus an early return from `onCreate`/`onTrimMemory` in the `:xray` process (Koin, the backend and AmneziaWG must not start there);
4. `di/NetworkModule.kt` — update checks against the fork's repository;
5. `SettingsScreen.kt` — the "Domain routing (Xray)" row;
6. `logcatter/.../LogcatStreamReader.kt` — no `--pid` filter, so the log export includes entries from the `:xray` process and earlier processes (logd only hands an app its own entries anyway).

The hooks are idempotent. If a merge hits a conflict in one of these files, the upstream version is taken and the hooks are applied again by their anchors.

## 3. Automatic releases

| Workflow | When | What it does |
| --- | --- | --- |
| `x-upstream-release` | cron every 15 min + manual | Fetches `releases/latest` from `wgtunnel/android` (this endpoint doesn't return drafts or pre-releases), checks the `X.Y.Z` format and whether a fork release already exists. Merges the tag, resolves conflicts by policy, builds, tests, publishes `X.Y.Z-x.1` |
| `x-release` | manual | Releases fork changes without a new upstream: `xRevision + 1` |
| `x-update-xray` | weekly | Updates `libv2ray.aar` to the latest release, commits only if the tests and config validation pass |
| `x-ci` | push / PR | Hooks in place, unit tests, config validation with a real Xray, debug build |

**Conflict policy** (`scripts/resolve-upstream-conflicts.sh`):

- upstream `.github/**` is deleted (upstream workflows aren't needed in the fork and must not run; new upstream workflows are removed too);
- `README.md` and `SECURITY.md` keep the fork's version (`.gitattributes`, `ours` driver);
- files with hooks take the upstream version, and the hooks are applied again;
- anything else: the merge is rolled back and an issue is opened with the list of files and the commands for a manual merge.

The mechanism was tested locally on synthetic releases: a changed and a new upstream workflow, changes next to a hook, a direct conflict on a hook line. In every case the release built without human input; an unrecoverable conflict rolls back cleanly with exit code 2.

**What to know about GitHub Actions:**

- Delay. The minimum schedule interval is 5 minutes, and runs get pushed back under load, so the real delay is usually 15–30 minutes after an upstream release. Reacting instantly to someone else's repository without its webhooks isn't possible.
- 60 days. In public repositories, schedules are disabled after 60 days without activity ([GitHub docs](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/disable-and-enable-workflows)). Upstream merges and the weekly Xray update create activity; on top of that, the workflow calls the `enable` API for its own schedules once a day.
- Forking with the Fork button. In forks of public repositories, schedules are off by default (same docs page), so `wgtunnelx` is created as a regular repository.
- Workflow permissions. If upstream commits change `.github/workflows`, `GITHUB_TOKEN` can't push them; a token with the `workflows` permission is needed (the `SYNC_TOKEN` secret, see [RELEASING.md](RELEASING.md)).

## 4. Quality checks

What was done and verified while building the fork:

- unit tests for the `:xray` module (17 tests): rule order, DNS, fake DNS, per-app rules, custom config, validator, import/export, geo database reading;
- **every preset and a config with every condition type pass `xray run -test` on Xray-core 26.10.21 with the real runetfreedom databases** — the same check CI runs before every release (`scripts/validate-xray-configs.sh`);
- preset categories were checked against the list of codes in the runetfreedom geosite/geoip files;
- the `assembleStandaloneDebug` build and the signed `assembleStandaloneRelease` build with R8: gomobile classes are kept, the manifest contains the fork's service and Activity, applicationId is `com.wgtunnelx.android`, the authority is `wgtunnelx.provider` (no clash with the original), and there are no geo files in the APK.

**On-device testing.** Version `5.8.2-x.1` was used day to day on a Pixel 11 Pro XL (Android 17), and that's where the Go runtime clash showed up (section 2.2). Before `5.8.2-x.2` shipped, its test build went through a manual pass over adb on the same phone:

- the app and Xray run in two separate processes;
- if the `:xray` process crashes, the VPN closes in about 0.1 s and the tunnel stops;
- if the app process crashes, `:xray` closes the VPN on its own in about 6 s;
- a normal stop takes about 50 ms;
- after a force stop, the auto-tunnel service starts again as soon as the app is opened;
- no Go runtime errors (`fatal error`) in the log.

So far only one device has been tested. Runs on other Android versions are still planned (section 6, stage 1).

## 5. Known limitations

1. Routing works in **VPN** mode. In "Local proxy" and "Lockdown" modes the tunnel starts the way it does upstream.
2. On the tunnel card and in the notification, a routed tunnel shows up as a proxy with the internal address `127.0.0.1:port`. Fixing this needs changes to the upstream UI, so it's postponed.
3. The tunnel's `AllowedIPs` limit what can go through `proxy`. Routing needs `0.0.0.0/0, ::/0`; private networks from `AllowedIPs` are automatically pinned to the tunnel ahead of the "local network direct" rule.
4. Per-app rules need Android 10+ (`ConnectivityManager.getConnectionOwnerUid`).
5. APK size: the Xray core adds about 33 MB of native code per architecture.
6. The routing screen is a separate Activity, so the app's PIN lock doesn't cover it (the rules contain no secrets).
7. If the tunnel has no IPv6, every IPv6 connection outside the local network is refused at once, so apps fall back to IPv4 right away. The VPN interface still takes IPv6 so it can't leak, and DNS answers without AAAA records anyway. Before 5.8.2-x.3 such connections were accepted and failed seconds later, and Telegram kept retrying its IPv6 servers.
8. The Russian presets send the Telegram apps (`org.telegram.messenger`, `.web`, `.beta`, Telegram X) through the tunnel by app: Telegram dials its servers by address, IPv4 and IPv6, so domain and IP lists miss part of it, and whatever goes direct is blocked. Settings saved from these presets by an older version get the rule once, when the app updates.

## 6. Roadmap

**Stage 1 — first release.**

Done:
- the repository, secrets and automatic releases are set up;
- `5.8.2-x.1` came out on October 23, 2026, and `5.8.2-x.2` on October 25 (Xray in a separate process, auto-tunnel watchdog, log entries with fixed tags);
- a manual pass on a Pixel 11 Pro XL with Android 17 (section 4).

Still to do:
- manual testing on 2–3 more devices (Android 8, 10, 13, 15/16): start/stop, Wi-Fi ↔ mobile switching, always-on VPN, auto-tunnel, an AmneziaWG config, geo database updates, an IPv6 network, per-app rules;
- measure battery and memory use with the runetfreedom database.

**Stage 2 — usability (2–4 weeks).**
- "Xray routing" status on the tunnel card and in the notification instead of "proxy".
- Rule tester: enter a domain → see which rule matches (via Xray's `routing` API).
- Rule profiles and a profile choice per tunnel.
- Rules backed up together with the WG Tunnel backup.

**Stage 3 — extensions.**
- Routing on top of Lockdown mode.
- Several tunnels as separate outbounds (for example, `geosite:youtube` → tunnel A, `geosite:openai` → tunnel B).
- Additional Xray outbounds (VLESS/Reality) as separate rule targets.

### Auto-tunnel in the fork

`AutoTunnelWatchdog` adds to the upstream auto-tunnel without changing its logic:

- if auto-tunnel is on in the settings but its service isn't running (the process was force-stopped, the vendor's task manager killed it, or the app crashed twice in a row and Android decided not to restart the service), the service starts again as soon as the app comes to the foreground. The fork also schedules the upstream `ServiceWorker`, which checks the same thing every 15 minutes (upstream declares it but never starts it). After a force stop, Android cancels that job too until the app is launched again, and there's no way around that;
- in release builds the upstream log has no auto-tunnel decisions (debug entries are dropped). The fork logs at INFO level with fixed tags `WgxAutoTunnel` and `WgxRouting` (R8 renames classes, so class-based tags are unreadable in release builds): the service state, the network it sees (type, first letter of the SSID, `usable`, VPN state) and every tunnel start/stop with its source (`USER` or `AUTO_TUNNEL`).

## 7. Risks and how they're covered

| Risk | Mitigation |
| --- | --- |
| Upstream rewrote code next to the hooks | Hooks are applied by anchors; if an anchor is gone, an issue is opened for a manual merge |
| Upstream changed the `TunnelProvider` / backend API | The CI build fails before publishing, an issue is opened, the previous release stays current |
| A new Xray version changes the config schema | The weekly update has to pass `xray run -test` on every preset before it's committed |
| Categories disappeared from the geo databases | Validator before the tunnel starts + a clear error in the UI |
| A compromised `libv2ray.aar` | The version is pinned, SHA-256 checking is supported (`xray/libxray.sha256`), the AAR can be built from source |
| Two Go runtimes in one process | Xray runs in the `:xray` process; process death is handled on both sides |
| Leaks around the rules | All IPv4 and IPv6 traffic enters the TUN; DNS on port 53 is intercepted by Xray's DNS module |
