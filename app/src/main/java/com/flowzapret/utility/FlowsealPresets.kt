package com.flowzapret.utility

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.flowzapret.components.ComponentStore
import com.flowzapret.data.Command
import org.json.JSONObject
import java.io.File

/**
 * Flowseal strategies adapted to the ByeDPI command line.
 *
 * Данные вынесены в `assets/flowseal_presets.json`, поэтому набор пресетов
 * можно обновлять без пересборки APK: если в `filesDir/components/presets/`
 * установлена новая версия, она имеет приоритет над встроенной.
 *
 * Each preset is a sequence of ByeDPI option groups. A group is limited by
 * protocol (-K), port (-V) and, for TCP, by a domain whitelist (-H).
 * Groups are separated by `-A n`, so every request is handled by the first
 * group whose filters match it and the following groups are fallbacks.
 *
 * Direct mappings:
 *  - multisplit / split positions      -> -s <pos>
 *  - multidisorder / disorder          -> -d <pos>
 *  - fake / fake TLS                   -> -f <pos> with -t (TTL), -Qr, -n
 *  - QUIC / UDP fakes (repeats)        -> -a <count>
 *  - hostlist domains                  -> -H:"{list:...}" / -H:host
 *  - port ranges                       -> -V
 *
 * Emulated, because ByeDPI has no direct analogue:
 *  - seqovl                 -> extra split positions (-s1:2:1 or -s1 -s3)
 *  - badseq                 -> fake plus disorder and a custom TTL
 *  - ip-id=zero             -> omitted
 *  - fakedsplit / hostfakesplit -> fake + disorder (or split + fake)
 *  - filter-l7=discord,stun -> UDP port ranges (-V)
 *  - any-protocol / cutoff  -> omitted
 *  - syndata                -> multidisorder (-d1 -d3 -d5)
 */
object FlowsealPresets {

    private const val TAG = "FlowsealPresets"
    private const val ASSET_NAME = "flowseal_presets.json"

    data class Preset(
        val id: String,
        val name: String,
        val description: String,
        val args: String
    ) {
        fun toCmdString(): String = args.trim()
    }

    @Volatile
    private var cached: List<Preset>? = null

    @Volatile
    private var cachedKey: String? = null

    /**
     * Актуальный набор пресетов: установленный компонент, иначе встроенный.
     * Результат кешируется до изменения файла.
     */
    fun presets(context: Context): List<Preset> {
        val override = ComponentStore.activePresets(context)
        val key = override?.let { "${it.absolutePath}:${it.lastModified()}" } ?: "asset"
        cached?.let { if (key == cachedKey) return it }

        val loaded = read(override, context) ?: read(null, context) ?: emptyList()
        cached = loaded
        cachedKey = key
        return loaded
    }

    fun getById(context: Context, id: String): Preset? =
        presets(context).find { it.id == id }

    /** Сбрасывает кеш (после установки/сброса компонента). */
    fun invalidate() {
        cached = null
        cachedKey = null
    }

    private fun read(file: File?, context: Context): List<Preset>? {
        val text = try {
            if (file != null) {
                file.readText(Charsets.UTF_8)
            } else {
                context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot read presets from ${file ?: ASSET_NAME}", t)
            return null
        }

        return try {
            val root = JSONObject(text)
            val array = root.optJSONArray("presets") ?: return null
            val result = ArrayList<Preset>(array.length())
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                val description = item.optString("description").trim()
                val args = item.optString("args").trim()
                if (id.isNotEmpty() && name.isNotEmpty() && args.isNotEmpty()) {
                    result.add(Preset(id, name, description, args))
                }
            }
            result.ifEmpty { null }
        } catch (t: Throwable) {
            Log.e(TAG, "Malformed presets JSON", t)
            null
        }
    }

    /**
     * Adds the Flowseal presets to the command history as pinned, named
     * entries. Safe to call on every launch:
     *  - existing presets are kept and updated by name,
     *  - renamed/edited old versions with the same preset name are removed,
     *  - user commands are never touched.
     */
    fun seed(context: Context, historyUtils: HistoryUtils) {
        val presetList = presets(context)
        if (presetList.isEmpty()) return

        val prefs = context.getPreferences()
        val history = historyUtils.getHistory().toMutableList()

        val presetNames = presetList.map { it.name }.toSet()

        // Drop stale versions of our own presets (same name, different text).
        history.removeAll { command ->
            val name = command.name
            name != null && name in presetNames &&
                presetList.none { it.name == name && it.toCmdString() == command.text }
        }

        var insertAt = 0
        presetList.forEach { preset ->
            val cmd = preset.toCmdString()
            val existing = history.find { it.text == cmd }
            if (existing == null) {
                history.add(insertAt++, Command(cmd, pinned = true, name = preset.name))
            } else {
                existing.pinned = true
                existing.name = preset.name
            }
        }

        historyUtils.saveHistory(history)

        // Apply the General preset as the default strategy on first launch.
        if (!prefs.getBoolean("flowseal_presets_seeded", false)) {
            val general = presetList.first()
            val current = prefs.getString("byedpi_cmd_args", null)
            if (current.isNullOrBlank() || current == DEFAULT_CMD_ARGS) {
                prefs.edit {
                    putString("byedpi_cmd_args", general.toCmdString())
                    putBoolean("byedpi_enable_cmd_settings", true)
                }
            }
        }
        prefs.edit { putBoolean("flowseal_presets_seeded", true) }
    }
}
