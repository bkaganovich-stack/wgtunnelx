# Сторонние компоненты WG Tunnel X

**Русский** · [English](NOTICE.md)

| Компонент | Лицензия | Исходники |
| --- | --- | --- |
| WG Tunnel (основа приложения) | MIT | https://github.com/wgtunnel/android |
| Ядро WG Tunnel (бэкенд AmneziaWG, hev-socks5-tunnel) | MIT и лицензии входящих в него компонентов | https://github.com/wgtunnel/core |
| Xray-core | MPL-2.0 | https://github.com/XTLS/Xray-core |
| AndroidLibXrayLite (`libv2ray.aar`, обёртка gomobile над Xray-core) | LGPL-3.0 | https://github.com/2dust/AndroidLibXrayLite |

`libv2ray.aar` используется без изменений, как отдельная динамически подключаемая библиотека
(`libgojni.so`). Её исходный код и инструкции по сборке доступны по ссылке выше. Точный релиз
закреплён в `xray/libxray.version`, и `xray/libs/libv2ray.aar` можно заменить любой совместимой
сборкой.

Гео-базы (`geoip.dat`, `geosite.dat`) в приложение не входят. Они загружаются во время работы
из источника, который выбрал пользователь:
- https://github.com/runetfreedom/russia-v2ray-rules-dat
- https://github.com/Loyalsoldier/v2ray-rules-dat
- https://github.com/v2fly/geoip и https://github.com/v2fly/domain-list-community
