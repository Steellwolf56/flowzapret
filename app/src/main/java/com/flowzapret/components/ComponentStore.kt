package com.flowzapret.components

import android.content.Context
import android.os.Build
import android.util.Log
import com.flowzapret.App
import java.io.File

/**
 * Хранилище установленных компонентов:
 *
 * ```
 * filesDir/components/<name>/<version>/<abi>/lib<name>.so
 * filesDir/components/presets/<version>/flowseal_presets.json
 * filesDir/components/<name>/active          <- текущая версия
 * ```
 *
 * Установка атомарная: файлы сначала пишутся во временный каталог, затем
 * переименовываются, и только после этого обновляется `active`. Предыдущие
 * версии остаются на диске — это даёт откат.
 */
object ComponentStore {

    private const val TAG = "ComponentStore"
    private const val ROOT = "components"

    fun root(context: Context = App.context()): File = File(context.filesDir, ROOT)

    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: ""

    fun installedVersion(name: String, context: Context = App.context()): String? {
        val file = File(root(context), "$name/active")
        if (!file.isFile) return null
        return runCatching { file.readText().trim() }.getOrNull()?.ifBlank { null }
    }

    fun installedDir(name: String, context: Context = App.context()): File? {
        val version = installedVersion(name, context) ?: return null
        val dir = File(root(context), "$name/$version")
        return dir.takeIf { it.isDirectory }
    }

    fun activeLib(
        name: String,
        abi: String = abi(),
        context: Context = App.context(),
    ): File? {
        val dir = installedDir(name, context) ?: return null
        val file = File(dir, "$abi/lib$name.so")
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    fun activePresets(context: Context = App.context()): File? {
        val dir = installedDir("presets", context) ?: return null
        val file = File(dir, "flowseal_presets.json")
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * Устанавливает компонент.
     *
     * @param files пары «путь внутри каталога версии» -> «исходный файл».
     */
    fun install(
        name: String,
        version: String,
        files: List<Pair<String, File>>,
        context: Context = App.context(),
    ) {
        require(isSafeName(name)) { "unsafe component name" }
        require(isSafeName(version)) { "unsafe component version" }

        val base = File(root(context), name)
        val target = File(base, version)
        val tmp = File(base, ".tmp-$version-${System.nanoTime()}")

        try {
            if (!tmp.mkdirs()) error("cannot create $tmp")
            for ((relative, source) in files) {
                val dest = File(tmp, relative)
                dest.parentFile?.mkdirs()
                source.copyTo(dest, overwrite = true)
            }
            target.deleteRecursively()
            base.mkdirs()
            if (!tmp.renameTo(target)) {
                tmp.copyRecursively(target, overwrite = true)
                tmp.deleteRecursively()
            }
            File(base, "active").writeText(version)
            Log.i(TAG, "Installed $name $version")
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Помечает установленную версию сломанной — загрузчик вернётся к встроенной. */
    fun markBroken(name: String, context: Context = App.context()) {
        val file = File(root(context), "$name/active")
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Cannot clear active version for $name")
        }
    }

    /** Удаляет все установленные компоненты (сброс к встроенным). */
    fun reset(context: Context = App.context()) {
        root(context).deleteRecursively()
    }

    fun isSafeName(value: String): Boolean =
        value.isNotEmpty() &&
            value.length <= 64 &&
            value.all { it.isLetterOrDigit() || it in "._+-" } &&
            !value.startsWith(".")
}
