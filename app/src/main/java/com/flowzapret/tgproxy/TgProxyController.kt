package com.flowzapret.tgproxy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.flowzapret.R

/**
 * Сборка параметров и запуск/остановка Telegram-прокси.
 */
object TgProxyController {

    /** Клиенты Telegram, которым можно предложить ссылку прокси. */
    private val TELEGRAM_PACKAGES = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "com.radolyn.ayugram",
        "com.exteragram.messenger",
        "org.telegram.plus",
        "ir.ilmili.telegraph",
        "org.telegram.BifToGram",
        "tw.nekomimi.nekogram",
        "xyz.nextalone.nagram",
        "uz.unnarsx.cherrygram",
        "org.telegram.mdgram",
    )

    fun start(context: Context): Boolean {
        if (!TgProxyNative.isAvailable) {
            Toast.makeText(context, R.string.tg_abi_unsupported, Toast.LENGTH_LONG).show()
            return false
        }

        val settings = TgProxySettings(context)
        val port = settings.port.toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(context, R.string.tg_invalid_port, Toast.LENGTH_SHORT).show()
            return false
        }

        val intent = buildIntent(context, TgProxyService.ACTION_START, settings.toConfig())

        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (t: Throwable) {
            Toast.makeText(context, context.getString(R.string.tg_start_failed, t.message ?: ""), Toast.LENGTH_LONG).show()
            false
        }
    }

    /** Перезапуск с сохранёнными настройками (в т.ч. из уведомления). */
    fun restart(context: Context): Boolean {
        if (!TgProxyNative.isAvailable) return false
        val intent = buildIntent(
            context,
            TgProxyService.ACTION_RESTART,
            TgProxySettings(context).toConfig(),
        )
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (t: Throwable) {
            android.util.Log.w("TgProxyController", "Failed to restart Telegram proxy", t)
            false
        }
    }

    /** Собирает intent с полным набором extras — ничего не теряется. */
    fun buildIntent(
        context: Context,
        action: String,
        config: TgProxyConfig,
    ): Intent = Intent(context, TgProxyService::class.java).apply {
        this.action = action
        putExtra(TgProxyService.EXTRA_BIND_IP, config.bindIp)
        putExtra(TgProxyService.EXTRA_PORT, config.port)
        putExtra(TgProxyService.EXTRA_IPS, config.dcIps)
        putExtra(TgProxyService.EXTRA_POOL_SIZE, config.poolSize)
        putExtra(TgProxyService.EXTRA_BUFFER_SIZE, config.bufferSizeKb)
        putExtra(TgProxyService.EXTRA_CF_ENABLED, config.cfEnabled)
        putExtra(TgProxyService.EXTRA_CF_DOMAIN, config.cfDomain)
        putExtra(TgProxyService.EXTRA_FAKE_TLS_DOMAIN, config.fakeTlsDomain)
        putExtra(TgProxyService.EXTRA_DISABLE_SECURE, config.disableSecure)
        putExtra(TgProxyService.EXTRA_FORCE_TEST_DC, config.forceTestDc)
        putExtra(TgProxyService.EXTRA_PROXY_PROTOCOL, config.proxyProtocol)
        putExtra(TgProxyService.EXTRA_SECRET, config.secret)
    }

    fun stop(context: Context) {
        try {
            context.startService(
                Intent(context, TgProxyService::class.java).setAction(TgProxyService.ACTION_STOP)
            )
        } catch (t: Throwable) {
            android.util.Log.w("TgProxyController", "Failed to stop Telegram proxy", t)
        }
    }

    /**
     * Секрет с префиксом для ссылки: `dd<hex>` либо `ee<hex><domain_hex>`.
     * Совпадает с тем, что возвращает нативная GetSecretWithPrefix().
     */
    fun secretForUrl(settings: TgProxySettings): String {
        val secret = settings.ensureSecret()
        val domain = settings.fakeTlsDomain
        return if (domain.isBlank()) {
            "dd$secret"
        } else {
            val hex = domain.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
            "ee$secret$hex"
        }
    }

    fun buildProxyUrl(settings: TgProxySettings): String {
        val host = settings.bindIp.ifBlank { "127.0.0.1" }
        val port = settings.port.toIntOrNull() ?: 1443
        return "https://t.me/proxy?server=$host&port=$port&secret=${secretForUrl(settings)}"
    }

    /** Открывает ссылку в установленном клиенте Telegram. */
    fun applyToTelegram(context: Context, url: String) {
        val pm = context.packageManager
        val available = TELEGRAM_PACKAGES.filter {
            try {
                pm.getPackageInfo(it, 0)
                true
            } catch (_: Exception) {
                false
            }
        }

        if (available.isEmpty()) {
            Toast.makeText(context, R.string.tg_no_clients, Toast.LENGTH_SHORT).show()
            return
        }

        val intents = available.map { pkg ->
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
        }

        try {
            if (intents.size == 1) {
                context.startActivity(intents.first().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                val chooser = Intent.createChooser(intents.first(), context.getString(R.string.tg_choose_client))
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            }
        } catch (_: Exception) {
            Toast.makeText(context, R.string.tg_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
}