<h1 align="center">
<img src="branding/icon-512.png" width="96" alt=""/><br/>
WG Tunnel X
</h1>

<div align="center">

A fork of [WG Tunnel](https://github.com/wgtunnel/android) that splits traffic by domain, the way Xray, v2rayNG and v2RayTun do.

[Русский](README.md) · **English**

</div>

## What it is

WG Tunnel is a WireGuard and AmneziaWG client for Android. Like any WireGuard client, it does split tunneling through `AllowedIPs` and the Android routing table, so you can only pick IP addresses and apps. Sites behind a CDN, addresses that keep changing and lists like "everything that's blocked" are close to impossible to describe that way.

WG Tunnel X adds the [Xray-core](https://github.com/XTLS/Xray-core) engine on top. Each connection gets its own decision: tunnel, direct or block. Rules can match:

- the domain, taken from TLS SNI, the HTTP Host header and QUIC (sniffing), so they work even for connections opened by IP;
- `geosite:` and `geoip:` database categories (`geosite:youtube`, `geosite:category-ru`, `geosite:ru-blocked`, `geoip:ru`, …);
- IP/CIDR, port, network (TCP/UDP), protocol (`http`, `tls`, `quic`, `bittorrent`) and app.

The rule syntax is the same as in Xray: `domain:`, `full:`, `keyword:`, `regexp:`, `geosite:`, `geoip:`, `ext:`. You can import rules from v2rayNG (custom routing) and from Xray configs, and export them too.

Everything else — AmneziaWG, auto-tunneling, Lockdown, proxy mode, tunnel recovery, tiles and integrations — works the same as in WG Tunnel. While routing is off, tunnels start exactly as they do in the original app.

## How to use it

1. Install the APK from [Releases](../../releases) (`…-arm64.apk` fits most phones). WG Tunnel X installs alongside WG Tunnel and updates from this repository.
2. Go to **Settings → Domain routing (Xray)** and turn the switch on.
3. Pick a template or add your own rules. There are two ready-made presets for Russia:
   - *Russian sites direct, everything else through the tunnel*;
   - *only blocked sites and popular foreign services through the tunnel* (`geosite:ru-blocked` and `geoip:ru-blocked` from the runetfreedom databases).
4. The geo databases are downloaded the first time you turn routing on and refreshed once a week. The default source is [runetfreedom/russia-v2ray-rules-dat](https://github.com/runetfreedom/russia-v2ray-rules-dat); you can switch to Loyalsoldier, v2fly or your own URLs.
5. Start the tunnel as usual, in **VPN** mode.

Before starting, the app checks your rules against the downloaded databases. If a `geosite:something` category isn't in the file, you get a clear error message instead of a core crash.

## How it works

```
Apps ─► TUN (WG Tunnel X VpnService) ─► Xray-core: sniffing + rules
                                          ├─ proxy  ─► SOCKS5 127.0.0.1 ─► AmneziaWG (userspace) ─► WG server
                                          ├─ direct ─► regular network (the app is excluded from its own VPN)
                                          ├─ block
                                          └─ dns-out ─► Xray DNS (tunneled domains are resolved through the tunnel)
```

The tunnel is brought up by the stock WG Tunnel backend in userspace mode (the same one "Local proxy" uses), so AmneziaWG obfuscation, recovery and statistics keep working unchanged. Xray-core runs in a separate process: AmneziaWG and Xray are both written in Go, and two Go runtimes in one process get in each other's way. Details, limitations and the roadmap are in [docs/PLAN.md](docs/PLAN.md) (in Russian).

## Updates

A new WG Tunnel X build is put together and published automatically shortly after each stable WG Tunnel release. GitHub Actions checks upstream every 15 minutes, merges the release, runs the tests, validates the configs against a real Xray-core and ships signed APKs. Versions look like `5.8.3-x.1`: the WG Tunnel version plus the fork revision. To set this up in your own repository, see [docs/RELEASING.en.md](docs/RELEASING.en.md).

## Building

You need JDK 21 and the Android SDK, same as for WG Tunnel.

```sh
./gradlew :app:assembleStandaloneDebug
./gradlew :xray:testDebugUnitTest
```

`libv2ray.aar` ([2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)) is downloaded on the first build, using the version in `xray/libxray.version`. You can also drop your own build into `xray/libs/libv2ray.aar`.

## Licenses and credits

- WG Tunnel — © Zane Schepke and contributors, MIT. The fork's code is MIT as well.
- Xray-core — MPL-2.0; AndroidLibXrayLite — LGPL-3.0 (shipped as the separate `libgojni.so` library, sources at the links above).
- Geo databases — runetfreedom, Loyalsoldier and v2fly projects, downloaded from their servers.

See [NOTICE.md](NOTICE.md) for more. WG Tunnel X is an unofficial project and has no affiliation with the WG Tunnel authors. Please report bugs in the fork here, not upstream.
