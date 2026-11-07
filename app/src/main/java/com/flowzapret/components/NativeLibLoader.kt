package com.flowzapret.components

import android.util.Log
import com.flowzapret.App

/**
 * Загружает нативную библиотеку из установленного компонента, если он есть,
 * иначе — встроенную из APK.
 *
 * Обновляемый `libbyedpi.so` содержит и ядро ByeDPI, и JNI-шимы, поэтому
 * имена пакетов/классов и сигнатуры `external fun` менять нельзя без
 * повышения `nativeApi` в манифесте компонентов.
 */
object NativeLibLoader {

    private const val TAG = "NativeLibLoader"
    private val loaded = HashSet<String>()

    @Synchronized
    fun load(name: String) {
        if (name in loaded) return

        val context = runCatching { App.context() }.getOrNull()
        if (context != null) {
            val updated = ComponentStore.activeLib(name, ComponentStore.abi(), context)
            if (updated != null) {
                try {
                    System.load(updated.absolutePath)
                    loaded.add(name)
                    Log.i(TAG, "Loaded $name from ${updated.absolutePath}")
                    return
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to load updated $name, falling back to bundled", t)
                    ComponentStore.markBroken(name, context)
                }
            }
        }

        System.loadLibrary(name)
        loaded.add(name)
    }
}
