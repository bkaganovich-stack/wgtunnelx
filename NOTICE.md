# Third-party components of WG Tunnel X

| Component | License | Source |
| --- | --- | --- |
| WG Tunnel (base application) | MIT | https://github.com/wgtunnel/android |
| WG Tunnel core (AmneziaWG backend, hev-socks5-tunnel) | MIT and bundled licenses | https://github.com/wgtunnel/core |
| Xray-core | MPL-2.0 | https://github.com/XTLS/Xray-core |
| AndroidLibXrayLite (`libv2ray.aar`, gomobile binding of Xray-core) | LGPL-3.0 | https://github.com/2dust/AndroidLibXrayLite |

`libv2ray.aar` is used unmodified as a separate dynamically loaded library (`libgojni.so`). Its
source code and build instructions are available at the link above; the exact release is pinned in
`xray/libxray.version`, and any compatible build can replace `xray/libs/libv2ray.aar`.

Geo databases (`geoip.dat`, `geosite.dat`) are not bundled. They are downloaded at runtime from the
source the user selects:
- https://github.com/runetfreedom/russia-v2ray-rules-dat
- https://github.com/Loyalsoldier/v2ray-rules-dat
- https://github.com/v2fly/geoip and https://github.com/v2fly/domain-list-community
