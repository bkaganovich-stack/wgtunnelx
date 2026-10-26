package com.wgtunnelx.xray.model

/**
 * Telegram connects to its data centres by IP address (IPv4 and IPv6), so domain lists never see
 * it and IP lists lag behind its addresses. Whatever is missed goes direct, where Telegram is
 * blocked, and the app hangs on "Connecting". Matching the apps themselves sends all of their
 * traffic through the tunnel, the way a SOCKS proxy set in Telegram would.
 */
object TelegramApps {
    val PACKAGES =
        listOf(
            "org.telegram.messenger", // Google Play
            "org.telegram.messenger.web", // APK from telegram.org
            "org.telegram.messenger.beta",
            "org.thunderdog.challegram", // Telegram X
        )

    val RULE =
        RoutingRule(
            id = "preset-telegram-apps",
            name = "Telegram apps",
            target = RouteTarget.TUNNEL,
            packages = PACKAGES,
        )
}

/**
 * Ready-made rule sets. Category names were checked against the runetfreedom, Loyalsoldier and
 * v2fly geo databases; categories marked RU-only exist only in the runetfreedom files.
 */
enum class RoutingPresets(val defaultTarget: RouteTarget, val rules: List<RoutingRule>) {
    /** Everything through the tunnel, except Russian services that block foreign IPs. */
    RUSSIA_DIRECT(
        defaultTarget = RouteTarget.TUNNEL,
        rules =
            listOf(
                RoutingRule(
                    id = "preset-ru-direct-domains",
                    name = "Russian sites direct",
                    target = RouteTarget.DIRECT,
                    domains =
                        listOf(
                            "geosite:category-ru",
                            "domain:ru",
                            "domain:su",
                            "domain:xn--p1ai",
                        ),
                ),
                TelegramApps.RULE,
                RoutingRule(
                    id = "preset-ru-direct-ip",
                    name = "Russian IPs direct",
                    target = RouteTarget.DIRECT,
                    ips = listOf("geoip:ru"),
                ),
            ),
    ),

    /** Only what is blocked in Russia goes through the tunnel; the rest stays direct. */
    RUSSIA_BLOCKED_ONLY(
        defaultTarget = RouteTarget.DIRECT,
        rules =
            listOf(
                RoutingRule(
                    id = "preset-ru-inside",
                    name = "Available only inside Russia",
                    target = RouteTarget.DIRECT,
                    // RU-only category
                    domains = listOf("geosite:ru-available-only-inside"),
                ),
                TelegramApps.RULE,
                RoutingRule(
                    id = "preset-ru-blocked",
                    name = "Blocked in Russia",
                    target = RouteTarget.TUNNEL,
                    // RU-only category
                    domains = listOf("geosite:ru-blocked"),
                ),
                RoutingRule(
                    id = "preset-popular",
                    name = "Popular foreign services",
                    target = RouteTarget.TUNNEL,
                    domains =
                        listOf(
                            "geosite:youtube",
                            "geosite:instagram",
                            "geosite:facebook",
                            "geosite:twitter",
                            "geosite:discord",
                            "geosite:telegram",
                            "geosite:openai",
                            "geosite:anthropic",
                        ),
                ),
                RoutingRule(
                    id = "preset-ru-blocked-ip",
                    name = "Blocked IP ranges",
                    target = RouteTarget.TUNNEL,
                    // ru-blocked is RU-only
                    ips = listOf("geoip:ru-blocked", "geoip:telegram"),
                ),
            ),
    ),

    /** Everything through the tunnel, ads and trackers are dropped. */
    TUNNEL_ALL_BLOCK_ADS(
        defaultTarget = RouteTarget.TUNNEL,
        rules =
            listOf(
                RoutingRule(
                    id = "preset-block-ads",
                    name = "Block ads",
                    target = RouteTarget.BLOCK,
                    domains = listOf("geosite:category-ads-all"),
                )
            ),
    ),

    /** Only explicitly listed services go through the tunnel. */
    DIRECT_ALL_EXCEPT_LISTED(
        defaultTarget = RouteTarget.DIRECT,
        rules =
            listOf(
                RoutingRule(
                    id = "preset-listed",
                    name = "Via tunnel",
                    target = RouteTarget.TUNNEL,
                    domains = listOf("geosite:youtube", "geosite:openai"),
                )
            ),
    ),
}
