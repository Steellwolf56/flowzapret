package com.flowzapret.activities

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.flowzapret.R
import com.flowzapret.databinding.ActivityTgLogBinding
import com.flowzapret.tgproxy.TgLogEntry
import com.flowzapret.tgproxy.TgLogLevel
import com.flowzapret.tgproxy.TgProxyLogManager
import com.flowzapret.utility.ClipboardUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Просмотр логов Telegram-прокси с фильтрами INFO / ERROR / NULL.
 */
class TgProxyLogActivity : BaseActivity() {

    private lateinit var binding: ActivityTgLogBinding
    private lateinit var timeFormat: SimpleDateFormat

    private val listener: (List<TgLogEntry>) -> Unit = { entries ->
        runOnUiThread { render(entries) }
    }

    private val saveLogs = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        writeToUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityTgLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        supportActionBar?.title = getString(R.string.tg_proxy_logs)

        timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val filterChanged = { _: android.widget.CompoundButton, _: Boolean -> refresh() }
        binding.tgLogInfoCheckbox.setOnCheckedChangeListener(filterChanged)
        binding.tgLogErrorCheckbox.setOnCheckedChangeListener(filterChanged)
        binding.tgLogNullCheckbox.setOnCheckedChangeListener(filterChanged)

        binding.tgLogClearBtn.setOnClickListener {
            TgProxyLogManager.clear()
        }

        binding.tgLogCopyBtn.setOnClickListener {
            val text = filteredText()
            if (text.isBlank()) {
                Toast.makeText(this, R.string.tg_logs_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ClipboardUtils.copy(this, text, getString(R.string.tg_proxy_logs))
        }

        binding.tgLogSaveBtn.setOnClickListener {
            if (filteredText().isBlank()) {
                Toast.makeText(this, R.string.tg_logs_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveLogs.launch("tg-proxy-${System.currentTimeMillis()}.log")
        }

        TgProxyLogManager.ensureStarted()
        TgProxyLogManager.addListener(listener)
    }

    private fun refresh() {
        render(TgProxyLogManager.snapshot())
    }

    private fun isEnabled(level: TgLogLevel): Boolean = when (level) {
        TgLogLevel.INFO -> binding.tgLogInfoCheckbox.isChecked
        TgLogLevel.ERROR -> binding.tgLogErrorCheckbox.isChecked
        TgLogLevel.NULL -> binding.tgLogNullCheckbox.isChecked
    }

    private fun filteredEntries(entries: List<TgLogEntry>): List<TgLogEntry> =
        entries.filter { isEnabled(it.level) }

    private fun filteredText(): String = filteredEntries(TgProxyLogManager.snapshot())
        .joinToString("\n") { format(it) }

    private fun format(entry: TgLogEntry): String {
        val level = when (entry.level) {
            TgLogLevel.INFO -> "I"
            TgLogLevel.ERROR -> "E"
            TgLogLevel.NULL -> "-"
        }
        val tag = entry.tag?.let { "$it: " } ?: ""
        return "${timeFormat.format(Date(entry.timestamp))} $level/$tag${entry.message}"
    }

    private fun render(entries: List<TgLogEntry>) {
        if (!::binding.isInitialized || isFinishing || isDestroyed) return
        val filtered = filteredEntries(entries)
        binding.tgLogText.text = filtered.joinToString("\n") { format(it) }
        binding.tgLogScroll.post {
            binding.tgLogScroll.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    private fun writeToUri(uri: Uri) {
        val text = filteredText()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) }
                runOnUiThread {
                    Toast.makeText(this@TgProxyLogActivity, R.string.tg_logs_saved, Toast.LENGTH_SHORT).show()
                }
            } catch (exception: Exception) {
                runOnUiThread {
                    Toast.makeText(this@TgProxyLogActivity, R.string.logs_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        TgProxyLogManager.removeListener(listener)
        super.onDestroy()
    }
}
