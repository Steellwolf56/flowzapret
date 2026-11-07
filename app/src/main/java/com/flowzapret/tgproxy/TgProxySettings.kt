package com.flowzapret.tgproxy

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.SecureRandom

/**
 * Настройки Telegram-прокси. Заменяют DataStore из tg-ws-proxy-android
 * на обычные SharedPreferences, чтобы не тянуть лишнюю зависимость.
 */
class TgProxySettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val FILE_NAME = "tg_proxy_settings"

        const val DEFAULT_DC2_IP = "149.154.167.220"
        const val DEFAULT_DC4_IP = "149.154.167.220"

        private const val KEY_DC_AUTO = "is_dc_auto"
        private const val KEY_EXPERIMENTAL = "is_experimental_mode"
        private const val KEY_PORT = "port"
        private const val KEY_BIND_IP = "bind_ip"
        private const val KEY_POOL_SIZE = "pool_size"
        private const val KEY_BUFFER_SIZE = "buffer_size_kb"
        private const val KEY_CF_ENABLED = "cfproxy_enabled"
        private const val KEY_CUSTOM_CF_ENABLED = "custom_cf_domain_enabled"
        private const val KEY_CUSTOM_CF_DOMAIN = "custom_cf_domain"
        private const val KEY_FAKE_TLS_DOMAIN = "fake_tls_domain"
        private const val KEY_DISABLE_SECURE = "disable_secure"
        private const val KEY_FORCE_TEST_DC = "force_test_dc"
        private const val KEY_PROXY_PROTOCOL = "proxy_protocol"
        private const val KEY_AUTO_START = "auto_start_on_boot"
        private const val KEY_SECRET = "secret_key"
        private const val KEY_SHOULD_RUN = "should_run"

        private val DC_KEYS = listOf(
            "dc1", "dc2", "dc3", "dc4", "dc5", "dc203",
            "dc1m", "dc2m", "dc3m", "dc4m", "dc5m", "dc203m",
        )

        private val DC_NUMBERS = listOf(1, 2, 3, 4, 5, 203, -1, -2, -3, -4, -5, -203)

        fun newSecret(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun isValidSecret(value: String): Boolean =
            value.length == 32 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
    }

    // ----- общие настройки -----

    var isDcAuto: Boolean
        get() = prefs.getBoolean(KEY_DC_AUTO, true)
        set(value) = prefs.edit { putBoolean(KEY_DC_AUTO, value) }

    var isExperimentalMode: Boolean
        get() = prefs.getBoolean(KEY_EXPERIMENTAL, false)
        set(value) = prefs.edit { putBoolean(KEY_EXPERIMENTAL, value) }

    var port: String
        get() = prefs.getString(KEY_PORT, "1443") ?: "1443"
        set(value) = prefs.edit { putString(KEY_PORT, value) }

    var bindIp: String
        get() = prefs.getString(KEY_BIND_IP, "127.0.0.1") ?: "127.0.0.1"
        set(value) = prefs.edit { putString(KEY_BIND_IP, value) }

    var poolSize: Int
        get() = prefs.getInt(KEY_POOL_SIZE, 4)
        set(value) = prefs.edit { putInt(KEY_POOL_SIZE, value) }

    var bufferSizeKb: Int
        get() = prefs.getInt(KEY_BUFFER_SIZE, 64)
        set(value) = prefs.edit { putInt(KEY_BUFFER_SIZE, value) }

    var cfEnabled: Boolean
        get() = prefs.getBoolean(KEY_CF_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_CF_ENABLED, value) }

    var customCfDomainEnabled: Boolean
        get() = prefs.getBoolean(KEY_CUSTOM_CF_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_CUSTOM_CF_ENABLED, value) }

    var customCfDomain: String
        get() = prefs.getString(KEY_CUSTOM_CF_DOMAIN, "") ?: ""
        set(value) = prefs.edit { putString(KEY_CUSTOM_CF_DOMAIN, value.trim()) }

    var fakeTlsDomain: String
        get() = prefs.getString(KEY_FAKE_TLS_DOMAIN, "") ?: ""
        set(value) = prefs.edit { putString(KEY_FAKE_TLS_DOMAIN, value.trim()) }

    var disableSecure: Boolean
        get() = prefs.getBoolean(KEY_DISABLE_SECURE, true)
        set(value) = prefs.edit { putBoolean(KEY_DISABLE_SECURE, value) }

    var forceTestDc: Boolean
        get() = prefs.getBoolean(KEY_FORCE_TEST_DC, false)
        set(value) = prefs.edit { putBoolean(KEY_FORCE_TEST_DC, value) }

    var proxyProtocol: Boolean
        get() = prefs.getBoolean(KEY_PROXY_PROTOCOL, false)
        set(value) = prefs.edit { putBoolean(KEY_PROXY_PROTOCOL, value) }

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_START, value) }

    var secretKey: String
        get() = prefs.getString(KEY_SECRET, "") ?: ""
        set(value) = prefs.edit { putString(KEY_SECRET, value.trim()) }

    /**
     * Флаг «прокси должен работать». Нужен, чтобы после пересоздания
     * процесса системой поднять прокси только если пользователь его
     * действительно включал, а не после явной остановки.
     */
    var shouldRun: Boolean
        get() = prefs.getBoolean(KEY_SHOULD_RUN, false)
        set(value) = prefs.edit { putBoolean(KEY_SHOULD_RUN, value) }

    // ----- DC -----

    fun getDc(key: String): String = prefs.getString(key, "") ?: ""

    fun setDc(key: String, value: String) = prefs.edit { putString(key, value.trim()) }

    fun dcKeys(): List<String> = DC_KEYS

    /** Гарантирует валидный секрет, генерируя новый при необходимости. */
    fun ensureSecret(): String {
        val current = secretKey
        if (isValidSecret(current)) return current
        val generated = newSecret()
        secretKey = generated
        return generated
    }

    /** Собирает строку `dc:ip,...` для нативной библиотеки. Пустая = авто. */
    fun buildDcIps(): String {
        if (isDcAuto) return ""
        val parts = mutableListOf<String>()
        for (i in DC_KEYS.indices) {
            // Без экспериментального режима используем только DC1–DC4.
            if (!isExperimentalMode && (i == 4 || i == 5 || i >= 6)) continue
            val ip = getDc(DC_KEYS[i])
            if (ip.isBlank()) continue
            parts.add("${DC_NUMBERS[i]}:$ip")
        }
        return parts.joinToString(",")
    }

    /** Полный снимок настроек для сервиса. */
    fun toConfig(): TgProxyConfig {
        val effectiveCfDomain = if (customCfDomainEnabled && cfEnabled) customCfDomain else ""
        return TgProxyConfig(
            bindIp = bindIp.ifBlank { "127.0.0.1" },
            port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: 1443,
            dcIps = buildDcIps(),
            poolSize = poolSize.coerceIn(1, 64),
            bufferSizeKb = bufferSizeKb.coerceIn(16, 1024),
            cfEnabled = cfEnabled,
            cfDomain = effectiveCfDomain,
            fakeTlsDomain = fakeTlsDomain,
            disableSecure = disableSecure,
            forceTestDc = forceTestDc,
            proxyProtocol = proxyProtocol,
            secret = ensureSecret(),
        )
    }

    /** Ссылка для Telegram. Использует секрет с префиксом из нативной библиотеки. */
    fun buildProxyUrl(secretWithPrefix: String?): String {
        val secret = secretWithPrefix ?: "dd${secretKey}"
        val host = bindIp.ifBlank { "127.0.0.1" }
        val p = port.toIntOrNull() ?: 1443
        return "https://t.me/proxy?server=$host&port=$p&secret=$secret"
    }

    /** Сохраняет все значения из формы одним заходом. */
    fun saveAll(values: Map<String, Any>) {
        prefs.edit {
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is String -> putString(key, value)
                    else -> Unit
                }
            }
        }
    }
}