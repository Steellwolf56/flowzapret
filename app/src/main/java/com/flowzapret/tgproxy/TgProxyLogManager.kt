package com.flowzapret.tgproxy

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

enum class TgLogLevel { INFO, ERROR, NULL }

data class TgLogEntry(
    val level: TgLogLevel,
    val message: String,
    val tag: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Простой буферизованный читатель logcat для процесса приложения.
 * Работает без Compose — отдаёт снапшоты подписчикам.
 */
object TgProxyLogManager {
    private const val TAG = "TgProxyLogManager"
    private const val MAX_ENTRIES = 500
    private const val UPDATE_INTERVAL_MS = 250L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val listeners = CopyOnWriteArrayList<(List<TgLogEntry>) -> Unit>()

    private var job: Job? = null
    private var process: Process? = null

    @Volatile
    private var entries: List<TgLogEntry> = emptyList()

    fun snapshot(): List<TgLogEntry> = entries

    fun addListener(listener: (List<TgLogEntry>) -> Unit) {
        listeners.add(listener)
        listener(entries)
    }

    fun removeListener(listener: (List<TgLogEntry>) -> Unit) {
        listeners.remove(listener)
    }

    fun ensureStarted() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                val pid = android.os.Process.myPid()
                val p = ProcessBuilder("logcat", "-v", "tag", "--pid", pid.toString())
                    .redirectErrorStream(true)
                    .start()
                process = p

                val pending = ArrayList<TgLogEntry>()
                p.inputStream.bufferedReader().use { reader ->
                    while (isActive) {
                        val line = try {
                            reader.readLine()
                        } catch (_: Exception) {
                            null
                        } ?: break
                        parseLine(line)?.let { pending.add(it) }
                        if (pending.size >= 20) {
                            flush(pending)
                        }
                    }
                }
                if (pending.isNotEmpty()) flush(pending)
            } catch (t: Throwable) {
                Log.w(TAG, "logcat reader stopped", t)
            } finally {
                process?.destroy()
                process = null
            }
        }
    }

    fun clear() {
        entries = emptyList()
        notifyListeners()
    }

    fun stop() {
        job?.cancel()
        job = null
        process?.destroy()
        process = null
    }

    private fun flush(pending: MutableList<TgLogEntry>) {
        if (pending.isEmpty()) return
        val merged = (entries + pending).takeLast(MAX_ENTRIES)
        pending.clear()
        entries = merged
        notifyListeners()
        // Небольшая пауза, чтобы UI не захлёбывался.
        try {
            Thread.sleep(UPDATE_INTERVAL_MS)
        } catch (_: InterruptedException) {
        }
    }

    private fun notifyListeners() {
        val snapshot = entries
        listeners.forEach { listener ->
            try {
                listener(snapshot)
            } catch (t: Throwable) {
                Log.w(TAG, "log listener failed", t)
            }
        }
    }

    /**
     * Формат `-v tag`: `E/TagName( 1234): message`.
     * INFO: I/D, ERROR: E/W, NULL: всё остальное (V, S, F без тега и т.п.).
     */
    private fun parseLine(raw: String): TgLogEntry? {
        if (raw.isBlank()) return null
        if (raw.startsWith("--------- beginning of")) return null

        val match = TAG_LINE.find(raw)
        if (match != null) {
            val (priorityChar, tag, message) = match.destructured
            if (message.isBlank()) return null
            return TgLogEntry(
                level = levelOf(priorityChar.firstOrNull()),
                message = message.trim(),
                tag = tag.trim().ifBlank { null },
            )
        }

        val priorityChar = raw.firstOrNull() ?: return null
        var message = raw
        val colon = raw.indexOf(':')
        if (colon in 1..80) {
            message = raw.substring(colon + 1).trim()
        }
        if (message.isBlank()) return null
        return TgLogEntry(level = levelOf(priorityChar), message = message)
    }

    private fun levelOf(priorityChar: Char?): TgLogLevel = when (priorityChar) {
        'E', 'W' -> TgLogLevel.ERROR
        'I', 'D' -> TgLogLevel.INFO
        else -> TgLogLevel.NULL
    }

    private val TAG_LINE = Regex("^([VDIWEF])/([^(]*?)(?:\\(\\s*\\d+\\))?: (.*)$")

    fun formatEntry(entry: TgLogEntry): String {
        val level = when (entry.level) {
            TgLogLevel.INFO -> "I"
            TgLogLevel.ERROR -> "E"
            TgLogLevel.NULL -> "-"
        }
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(entry.timestamp))
        val tag = entry.tag?.let { "$it: " } ?: ""
        return "$time $level/$tag${entry.message}"
    }
}