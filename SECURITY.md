# Security Policy

**English** · [Русский](SECURITY.ru.md)

WG Tunnel X is an unofficial fork of [WG Tunnel](https://github.com/wgtunnel/android), maintained separately from it. Security reports about the fork go here, not to the WG Tunnel authors.

## Reporting a vulnerability

Please don't open a public issue. Report it privately through GitHub instead: **[Report a vulnerability](https://github.com/bkaganovich-stack/wgtunnelx/security/advisories/new)** (the *Security* tab → *Report a vulnerability*). Only you and the maintainers can see the report, and the fix and the advisory can be worked out right there.

Helpful details:

- the WG Tunnel X version (for example, `5.8.2-x.2`), the device and the Android version;
- what an attacker can do and under what conditions;
- steps to reproduce or a proof of concept;
- logs, if they help. Remove private keys, server addresses and other personal data first.

## Scope

This repository covers what the fork adds on top of WG Tunnel:

- domain routing: the Xray-core integration, the `:xray` process and its VPN service, the routing rules and settings, geo database downloads;
- the update check against this repository;
- release builds, signing and the GitHub Actions automation.

A vulnerability in WG Tunnel itself that affects the original app too should go to WG Tunnel under [its security policy](https://github.com/wgtunnel/android/security/policy). Issues in [Xray-core](https://github.com/XTLS/Xray-core/security/policy) and [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite) belong to those projects. If you're not sure where a problem belongs, report it here.

## Supported versions

Only the latest release gets security fixes. WG Tunnel X follows WG Tunnel's stable releases, so please update to the [latest release](https://github.com/bkaganovich-stack/wgtunnelx/releases/latest) before reporting.

## Verifying releases

All release APKs are signed with the same key. Every release lists the signing certificate fingerprint and a `SHA256SUMS` file, so you can check that an APK came from this repository and wasn't modified.
