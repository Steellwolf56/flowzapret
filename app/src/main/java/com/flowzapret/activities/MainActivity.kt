package com.flowzapret.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.flowzapret.R
import com.flowzapret.services.ServiceManager
import com.flowzapret.services.TestService
import com.flowzapret.tgproxy.TgProxyController
import com.flowzapret.ui.MainAdapter
import com.flowzapret.utility.ClipboardUtils
import com.flowzapret.utility.DiagnosticUtils
import com.flowzapret.utility.DomainListUtils
import com.flowzapret.utility.FlowsealPresets
import com.flowzapret.utility.HistoryUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import kotlin.system.exitProcess

/**
 * Хост приложения: тулбар, две свайпаемые вкладки и общее меню.
 * Логика вкладки «Обход DPI» живёт в [PageBypassFragment],
 * логика Telegram-прокси — в [TelegramFragment].
 */
class MainActivity : BaseActivity() {

    private lateinit var historyUtils: HistoryUtils
    private lateinit var pager: androidx.viewpager2.widget.ViewPager2

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName

        /** Индекс вкладки Telegram для перехода из уведомления. */
        const val EXTRA_OPEN_TAB = "open_tab"

        private fun collectLogs(): String? {
            return try {
                val process = Runtime.getRuntime().exec("logcat *:D -d")
                process.inputStream.bufferedReader().use { reader -> reader.readText() }
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to collect logs", exception)
                null
            }
        }
    }

    private val logsRegister = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
        ::handleLogsFileResult
    )

    private fun handleLogsFileResult(result: ActivityResult) {
        if (result.resultCode != RESULT_OK) return
        val data = result.data ?: return
        val uri = data.data
        val path = data.getStringExtra(FileActivity.EXTRA_PATH)
        val file = if (path == null) null else File(path)
        if (uri == null && file == null) return

        lifecycleScope.launch(Dispatchers.IO) {
            val logs = collectLogs()
            if (logs == null) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.logs_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            try {
                val outputStream = when {
                    uri != null -> contentResolver.openOutputStream(uri)
                    file != null -> file.outputStream()
                    else -> null
                }
                if (outputStream == null) {
                    Log.e(TAG, "Failed to open output stream")
                    return@launch
                }
                outputStream.use { it.write(logs.toByteArray()) }
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to save logs", exception)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, R.string.logs_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceManager.refresh(this)

        setContentView(R.layout.activity_main)
        setupToolbar()

        historyUtils = HistoryUtils(this)

        val adapter = MainAdapter(this)
        adapter.addPage(getString(R.string.bypass_dpi))
        adapter.addPage(getString(R.string.telegram))

        pager = findViewById(R.id.viewPager)
        pager.adapter = adapter
        pager.offscreenPageLimit = 1

        applyOpenTab(intent)

        val tabs = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)
        com.google.android.material.tabs.TabLayoutMediator(tabs, pager) { tab, position ->
            tab.text = adapter.getPageTitle(position)
        }.attach()

        DomainListUtils.syncLists(this)

        lifecycleScope.launch(Dispatchers.IO) {
            FlowsealPresets.seed(this@MainActivity, historyUtils)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyOpenTab(intent)
    }

    private fun applyOpenTab(intent: Intent?) {
        val target = intent?.getIntExtra(EXTRA_OPEN_TAB, 0) ?: return
        val count = pager.adapter?.itemCount ?: return
        if (target in 0 until count) {
            pager.setCurrentItem(target, false)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_components -> {
                startActivity(Intent(this, ComponentsActivity::class.java))
                true
            }

            R.id.action_diagnostics -> {
                showDiagnostics()
                true
            }

            R.id.action_save_logs -> {
                val intent = Intent(this, FileActivity::class.java)
                intent.putExtra(FileActivity.EXTRA_MODE, FileActivity.MODE_CREATE)
                intent.putExtra(FileActivity.EXTRA_TYPE, FileActivity.TYPE_LOGS)
                logsRegister.launch(intent)
                true
            }

            R.id.action_close_app -> {
                TestService.stop()
                TgProxyController.stop(this)
                lifecycleScope.launch {
                    try {
                        TestService.awaitStopped()
                        ServiceManager.waitStop()
                    } catch (exception: Exception) {
                        Log.e(TAG, "Failed to stop service before closing", exception)
                        return@launch
                    }
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                    exitProcess(0)
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showDiagnostics() {
        val report = DiagnosticUtils.buildReport(this)
        val padding = (24 * resources.displayMetrics.density).toInt()

        val textView = TextView(this).apply {
            text = report
            setPadding(padding, padding / 2, padding, padding / 2)
        }

        val scrollView = ScrollView(this).apply { addView(textView) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics)
            .setView(scrollView)
            .setPositiveButton(R.string.diagnostic_copy) { _, _ ->
                ClipboardUtils.copy(this, report, getString(R.string.diagnostics))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val copyButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            copyButton.nextFocusLeftId = cancelButton.id
            copyButton.nextFocusRightId = cancelButton.id
            cancelButton.nextFocusLeftId = copyButton.id
            cancelButton.nextFocusRightId = copyButton.id
            copyButton.requestFocus()
        }
        dialog.show()
    }
}
