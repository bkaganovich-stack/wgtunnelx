<h1 align="center">
<img src="branding/icon-512.png" width="96" alt=""/><br/>
WG Tunnel X
</h1>

<div align="center">

Форк [WG Tunnel](https://github.com/wgtunnel/android) с раздельной маршрутизацией трафика по доменам, как в Xray, v2rayNG и v2RayTun.<br/>
*A fork of WG Tunnel with Xray-style domain-based split routing (geosite / geoip).*

</div>

## Что это

WG Tunnel — клиент WireGuard и AmneziaWG для Android. Раздельное туннелирование в нём (как и в любом WireGuard-клиенте) делается через `AllowedIPs` и таблицу маршрутов Android, то есть только по IP-адресам и приложениям. Сайты за CDN, меняющиеся адреса и списки вида «всё заблокированное» так описать почти невозможно.

WG Tunnel X добавляет к нему движок [Xray-core](https://github.com/XTLS/Xray-core). Решение «туннель / напрямую / блокировать» принимается для каждого соединения:

- по домену — он берётся из TLS SNI, HTTP Host и QUIC (sniffing), поэтому правила работают даже для соединений, открытых по IP;
- по категориям баз `geosite:` и `geoip:` (`geosite:youtube`, `geosite:category-ru`, `geosite:ru-blocked`, `geoip:ru`, …);
- по IP/CIDR, порту, сети (TCP/UDP), протоколу (`http`, `tls`, `quic`, `bittorrent`) и приложению.

Синтаксис правил совпадает с Xray: `domain:`, `full:`, `keyword:`, `regexp:`, `geosite:`, `geoip:`, `ext:`. Правила импортируются из v2rayNG (custom routing) и из конфигов Xray, экспорт тоже есть.

Всё остальное — AmneziaWG, автотуннелирование, Lockdown, прокси-режим, восстановление туннеля, плитки и интеграции — остаётся таким же, как в WG Tunnel. Пока маршрутизация выключена, туннели запускаются ровно так же, как в оригинале.

## Как пользоваться

1. Установите APK из [Releases](../../releases) (`…-arm64.apk` подходит для большинства телефонов). WG Tunnel X ставится рядом с WG Tunnel и обновляется из этого репозитория.
2. **Настройки → Маршрутизация по доменам (Xray)** → включите переключатель.
3. Выберите шаблон или добавьте свои правила. Для России есть два готовых варианта:
   - *российские сайты напрямую, остальное через туннель*;
   - *через туннель только заблокированное и популярные зарубежные сервисы* (`geosite:ru-blocked`, `geoip:ru-blocked` из баз runetfreedom).
4. Гео-базы скачиваются при первом включении (источник по умолчанию — [runetfreedom/russia-v2ray-rules-dat](https://github.com/runetfreedom/russia-v2ray-rules-dat), можно выбрать Loyalsoldier, v2fly или свои ссылки) и обновляются раз в неделю.
5. Запустите туннель как обычно, в режиме **VPN**.

Перед запуском приложение сверяет правила с загруженными базами: если категории `geosite:что-то` в файле нет, вы увидите понятную ошибку вместо падения ядра.

## Как это устроено

```
Приложения ─► TUN (VpnService WG Tunnel X) ─► Xray-core: sniffing + правила
                                                 ├─ proxy  ─► SOCKS5 127.0.0.1 ─► AmneziaWG (userspace) ─► сервер WG
                                                 ├─ direct ─► обычная сеть (приложение исключено из своего VPN)
                                                 ├─ block
                                                 └─ dns-out ─► DNS Xray (домены «туннеля» резолвятся через туннель)
```

Туннель запускается штатным бэкендом WG Tunnel в userspace-режиме (тот же, что у «Локального прокси»), поэтому AmneziaWG-обфускация, восстановление и статистика работают без изменений. Подробности, ограничения и план — в [docs/PLAN.md](docs/PLAN.md).

## Обновления

Новая версия WG Tunnel X собирается и публикуется автоматически вскоре после выхода стабильного релиза WG Tunnel: GitHub Actions проверяет апстрим каждые 15 минут, вливает релиз, прогоняет тесты, проверяет конфиги настоящим Xray-core и выпускает подписанные APK. Версия выглядит как `5.8.3-x.1`: номер WG Tunnel плюс ревизия форка. Как настроить это в своём репозитории — [docs/RELEASING.md](docs/RELEASING.md).

## Сборка

Нужны JDK 21 и Android SDK, как для WG Tunnel.

```sh
./gradlew :app:assembleStandaloneDebug
./gradlew :xray:testDebugUnitTest
```

`libv2ray.aar` ([2dust/AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)) скачивается при первой сборке по версии из `xray/libxray.version`. Можно положить свою сборку в `xray/libs/libv2ray.aar`.

## Лицензии и благодарности

- WG Tunnel — © Zane Schepke и участники, MIT. Код форка — тоже MIT.
- Xray-core — MPL-2.0, AndroidLibXrayLite — LGPL-3.0 (подключается отдельной библиотекой `libgojni.so`, исходники по ссылкам выше).
- Гео-базы — проекты runetfreedom, Loyalsoldier и v2fly, загружаются с их серверов.

Подробнее — в [NOTICE.md](NOTICE.md). WG Tunnel X — неофициальный проект, авторы WG Tunnel к нему отношения не имеют; ошибки форка, пожалуйста, присылайте сюда, а не в апстрим.
