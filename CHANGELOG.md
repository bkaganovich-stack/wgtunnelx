# Changelog

Changes in WG Tunnel X releases, newest first. A version is `<WG Tunnel version>-x.<fork revision>`; what changed in WG Tunnel itself is in [its releases](https://github.com/wgtunnel/android/releases). Downloads and signing details are on the [releases page](https://github.com/bkaganovich-stack/wgtunnelx/releases).

## 5.9.1-x.2 — 2026-11-22

WG Tunnel 5.9.1, Xray-core v26.11.9.

- Strict split tunneling now also protects tunnels with domain routing. An app kept out of the VPN, or let in only for other apps, can no longer bind to the VPN network and reach the tunnel to learn your server's IP. As in WG Tunnel, connections whose app can't be identified are refused too, and it needs Android 10 or newer. A custom Xray config is used as written, without this protection.

## 5.9.1-x.1 — 2026-11-22

WG Tunnel 5.9.1, Xray-core v26.11.9.

- Based on WG Tunnel 5.9.1. It also brings everything from [WG Tunnel 5.9.0](https://github.com/wgtunnel/android/releases/tag/5.9.0), which WG Tunnel X skipped:
  - strict split tunneling: apps excluded from the VPN can't use the tunnel to learn your server's IP (Advanced settings; turned on automatically for SIM cards from Russia, suggested for the Russian locale);
  - a reworked PIN lock screen; dialogs no longer show over it;
  - the quick settings tile and shortcut start the tunnels you used last;
  - Android TV navigation fixes, crash fixes, fixes for DNS and dual stack in proxy and lockdown modes.
- Xray-core v26.11.9 (was v26.10.31).
- Known limitation: strict split tunneling doesn't work yet for a tunnel with domain routing turned on, where Xray owns the VPN. It works as in WG Tunnel for every other tunnel.

## 5.8.2-x.5 — 2026-11-12

WG Tunnel 5.8.2, Xray-core v26.10.31.

- New app icon: launcher, quick settings tile, notifications and TV banner.
- Xray-core v26.10.31 (was v26.10.21).

## 5.8.2-x.4 — 2026-10-26

WG Tunnel 5.8.2, Xray-core v26.10.21.

- A routed tunnel that stops answering after a network change (for example, Wi-Fi to mobile data with the screen off) is restarted once per network, 5 seconds after it stops answering.
- QUIC (UDP 443) that would go through the tunnel is refused, so apps fall back to TCP. This keeps traffic off the tunnel's SOCKS5 UDP relay, which could crash the app. Settings → Domain routing → **No QUIC through the tunnel**, on by default.

## 5.8.2-x.3 — 2026-10-26

WG Tunnel 5.8.2, Xray-core v26.10.21.

- If the tunnel has no IPv6, IPv6 connections are refused at once instead of failing seconds later, so apps such as Telegram fall back to IPv4 right away.
- The Russian presets send the Telegram apps through the tunnel as a whole: Telegram connects to its servers by IP address, so site and address lists can't be relied on to catch it. Settings saved from those presets get the rule once.

## 5.8.2-x.2 — 2026-10-25

WG Tunnel 5.8.2, Xray-core v26.10.21.

- Xray-core runs in its own `:xray` process. AmneziaWG and Xray each bring a Go runtime, and in one process they could crash Xray with `fatal error: unknown caller pc` and leave the VPN up with no traffic. If either process dies, the VPN is closed and the tunnel stops.
- Auto-tunnel is restarted when it's enabled but not running, for example after the app crashed twice in a row and Android didn't restart it.
- Exported logs include the `:xray` process, and fork log lines keep readable tags in release builds.

## 5.8.2-x.1 — 2026-10-23

WG Tunnel 5.8.2, Xray-core v26.10.21.

The first WG Tunnel X release.

- Domain-based split routing powered by Xray-core: each connection goes to the tunnel, direct or is blocked, by domain (TLS SNI, HTTP Host and QUIC sniffing), `geosite:`/`geoip:` category, IP/CIDR, port, network, protocol or app.
- Rule syntax as in Xray; import from v2rayNG custom routing and Xray configs, and export.
- Presets for Russia: Russian sites direct, or only blocked sites and popular foreign services through the tunnel.
- Geo databases from runetfreedom (default), Loyalsoldier, v2fly or your own URLs, downloaded on first use and refreshed weekly. Rules are checked against them before the tunnel starts.
- Installs next to WG Tunnel (`com.wgtunnelx.android`) and updates itself from this repository.
