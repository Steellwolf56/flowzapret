package com.flowzapret.tgproxy

import android.util.Log
import com.flowzapret.App
import com.flowzapret.components.ComponentStore
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA-обёртка над Rust-библиотекой libtgwsproxy.so
 * (https://github.com/Flowseal/tg-ws-proxy, GPL-3.0).
 *
 * Библиотека поставляется только под armeabi-v7a и arm64-v8a. На других
 * архитектурах [isAvailable] вернёт false, и Telegram-вкладка покажет
 * пояснение вместо падения.
 *
 * Если в `filesDir/components/tgwsproxy/` установлена обновлённая версия,
 * загружается она; иначе — встроенная из APK.
 */
interface TgProxyLibrary : Library {
    fun StartProxy(host: String, port: Int, dcIps: String, secret: String, verbose: Int): Int
    fun StopProxy(): Int
    fun SetPoolSize(size: Int)
    fun SetBufferSizeKb(kb: Int)
    fun SetCfProxyCacheDir(cacheDir: String)
    fun SetCfProxyConfig(enabled: Int, priority: Int, userDomain: String)
    fun SetCfWorkerDomains(domains: String)
    fun SetFakeTlsDomain(domain: String)
    fun SetDisableSecure(v: Int)
    fun SetForceTestDc(v: Int)
    fun SetProxyProtocol(v: Int)
    fun GetSecretWithPrefix(): Pointer?
    fun GetStats(): Pointer?
    fun FreeString(p: Pointer)
}

object TgProxyNative {
    private const val TAG = "TgProxyNative"

    @Volatile
    private var initialized = false

    @Volatile
    private var library: TgProxyLibrary? = null

    private fun lib(): TgProxyLibrary? {
        if (initialized) return library
        synchronized(this) {
            if (initialized) return library
            initialized = true
            library = load()
        }
        return library
    }

    private fun load(): TgProxyLibrary? {
        val context = runCatching { App.context() }.getOrNull()
        val updated = context?.let {
            ComponentStore.activeLib("tgwsproxy", ComponentStore.abi(), it)
        }

        if (updated != null) {
            try {
                return Native.load(updated.absolutePath, TgProxyLibrary::class.java) as TgProxyLibrary
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load updated libtgwsproxy.so, falling back", t)
                ComponentStore.markBroken("tgwsproxy", context!!)
            }
        }

        return try {
            Native.load("tgwsproxy", TgProxyLibrary::class.java) as TgProxyLibrary
        } catch (t: Throwable) {
            Log.e(TAG, "libtgwsproxy.so is not available on this ABI", t)
            null
        }
    }

    /** Сбрасывает загруженную библиотеку (после обновления компонента). */
    @Synchronized
    fun reload() {
        library = null
        initialized = false
    }

    val isAvailable: Boolean
        get() = lib() != null

    fun startProxy(
        host: String,
        port: Int,
        dcIps: String,
        secret: String,
        verbose: Boolean,
    ): Int = lib()?.StartProxy(host, port, dcIps, secret, if (verbose) 1 else 0) ?: -1

    fun stopProxy(): Int = lib()?.StopProxy() ?: -1

    fun setPoolSize(size: Int) {
        lib()?.SetPoolSize(size)
    }

    fun setBufferSizeKb(kb: Int) {
        lib()?.SetBufferSizeKb(kb)
    }

    fun setCfProxyCacheDir(cacheDir: String) {
        lib()?.SetCfProxyCacheDir(cacheDir)
    }

    fun setCfProxyConfig(enabled: Boolean, priority: Boolean, userDomain: String) {
        lib()?.SetCfProxyConfig(if (enabled) 1 else 0, if (priority) 1 else 0, userDomain)
    }

    fun setCfWorkerDomains(domains: String) {
        lib()?.SetCfWorkerDomains(domains)
    }

    fun setFakeTlsDomain(domain: String) {
        lib()?.SetFakeTlsDomain(domain)
    }

    fun setDisableSecure(v: Boolean) {
        lib()?.SetDisableSecure(if (v) 1 else 0)
    }

    fun setForceTestDc(v: Boolean) {
        lib()?.SetForceTestDc(if (v) 1 else 0)
    }

    fun setProxyProtocol(v: Boolean) {
        lib()?.SetProxyProtocol(if (v) 1 else 0)
    }

    /** Полный секрет с префиксом: `dd<hex>` или `ee<hex><domain_hex>`. */
    fun getSecretWithPrefix(): String? = withString(lib()?.GetSecretWithPrefix())

    /** Строка статистики: `up=.. down=.. active=..`. */
    fun getStats(): String? = withString(lib()?.GetStats())

    private fun withString(pointer: Pointer?): String? {
        val library = lib() ?: return null
        if (pointer == null) return null
        return try {
            pointer.getString(0)
        } finally {
            library.FreeString(pointer)
        }
    }
}
