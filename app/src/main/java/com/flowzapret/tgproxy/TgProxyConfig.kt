package com.flowzapret.tgproxy

/**
 * Полный снимок настроек Telegram-прокси.
 *
 * Сервис больше не читает extras «как получится»: любая команда
 * (старт/рестарт) несёт этот снимок целиком, поэтому настройки не
 * сбрасываются при перезапуске из уведомления.
 */
data class TgProxyConfig(
    val bindIp: String = "127.0.0.1",
    val port: Int = 1443,
    val dcIps: String = "",
    val poolSize: Int = 4,
    val bufferSizeKb: Int = 64,
    val cfEnabled: Boolean = true,
    val cfDomain: String = "",
    val fakeTlsDomain: String = "",
    val disableSecure: Boolean = true,
    val forceTestDc: Boolean = false,
    val proxyProtocol: Boolean = false,
    val secret: String = "",
)

/**
 * Состояние сервиса для UI-переключателя.
 */
enum class TgProxyState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
}
