package com.flowzapret.activities

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.flowzapret.R
import com.flowzapret.components.ComponentManifest
import com.flowzapret.components.ComponentRepository
import com.flowzapret.components.ComponentStore
import com.flowzapret.databinding.ActivityComponentsBinding
import com.flowzapret.utility.FlowsealPresets
import kotlinx.coroutines.launch

/**
 * Экран управления обновляемыми компонентами: ByeByeDPI, tgws proxy
 * и пресеты Flowseal.
 */
class ComponentsActivity : BaseActivity() {

    private lateinit var binding: ActivityComponentsBinding

    private val importZip = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) importFromZip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComponentsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupToolbar()
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.componentsUrlEt.setText(ComponentRepository.manifestUrl(this))

        binding.componentsSaveUrlBtn.setOnClickListener { saveSource() }
        binding.componentsCheckBtn.setOnClickListener { checkUpdates() }
        binding.componentsUpdateBtn.setOnClickListener { updateAll() }
        binding.componentsResetBtn.setOnClickListener { resetToBundled() }
        binding.componentsImportBtn.setOnClickListener {
            runCatching { importZip.launch(arrayOf("application/zip", "application/octet-stream")) }
                .onFailure {
                    Toast.makeText(this, R.string.file_picker_unavailable, Toast.LENGTH_LONG).show()
                }
        }

        renderStatus()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun saveSource() {
        val url = binding.componentsUrlEt.text.toString().trim()
        if (!url.startsWith("https://")) {
            Toast.makeText(this, R.string.components_invalid_url, Toast.LENGTH_SHORT).show()
            return
        }
        ComponentRepository.setManifestUrl(this, url)
        Toast.makeText(this, R.string.components_source_saved, Toast.LENGTH_SHORT).show()
    }

    private fun checkUpdates() {
        if (!saveSourceIfValid()) return
        setBusy(true, getString(R.string.components_checking))
        lifecycleScope.launch {
            try {
                val manifest = ComponentRepository.fetchManifest(this@ComponentsActivity)
                val updates = ComponentRepository.ALL_COMPONENTS.filter { name ->
                    val entry = manifest.components[name]
                    entry != null && ComponentStore.installedVersion(name, this@ComponentsActivity) != entry.version
                }
                val text = if (updates.isEmpty()) {
                    getString(R.string.components_none)
                } else {
                    updates.joinToString("\n") { name ->
                        val version = manifest.components[name]?.version.orEmpty()
                        "${displayName(name)}: $version"
                    }
                }
                binding.componentsStatusTv.text = text
            } catch (t: Throwable) {
                binding.componentsStatusTv.text = getString(R.string.components_failed, t.message ?: "")
            } finally {
                setBusy(false, null)
            }
        }
    }

    private fun updateAll() {
        if (!saveSourceIfValid()) return
        setBusy(true, getString(R.string.components_updating))
        lifecycleScope.launch {
            try {
                val url = ComponentRepository.manifestUrl(this@ComponentsActivity)
                val manifest = ComponentRepository.fetchManifest(this@ComponentsActivity, url)
                val result = ComponentRepository.install(
                    context = this@ComponentsActivity,
                    manifest = manifest,
                    baseUrl = url,
                    onProgress = { name ->
                        binding.componentsStatusTv.text =
                            getString(R.string.components_updating) + " " + displayName(name)
                    },
                )
                binding.componentsStatusTv.text = summarize(result)
                renderStatus()
            } catch (t: Throwable) {
                binding.componentsStatusTv.text = getString(R.string.components_failed, t.message ?: "")
            } finally {
                setBusy(false, null)
            }
        }
    }

    private fun importFromZip(uri: Uri) {
        setBusy(true, getString(R.string.components_updating))
        lifecycleScope.launch {
            try {
                val result = ComponentRepository.installFromZip(this@ComponentsActivity, uri)
                binding.componentsStatusTv.text = summarize(result)
                renderStatus()
            } catch (t: Throwable) {
                binding.componentsStatusTv.text = getString(R.string.components_failed, t.message ?: "")
            } finally {
                setBusy(false, null)
            }
        }
    }

    private fun resetToBundled() {
        ComponentStore.reset(this)
        FlowsealPresets.invalidate()
        renderStatus()
        binding.componentsStatusTv.text = getString(R.string.components_reset_done)
    }

    private fun summarize(result: ComponentRepository.UpdateResult): String {
        return when {
            result.errors.isNotEmpty() -> result.errors.entries.joinToString("\n") { (name, error) ->
                "${displayName(name)}: ${getString(R.string.components_failed, error)}"
            }

            result.updated.isNotEmpty() -> getString(
                R.string.components_updated,
                result.updated.joinToString(", ") { displayName(it) },
            )

            else -> getString(R.string.components_none)
        }
    }

    private fun renderStatus() {
        binding.componentsByedpiTv.text = componentLine(ComponentRepository.BYEDPI)
        binding.componentsTgwsTv.text = componentLine(ComponentRepository.TGWS)
        binding.componentsPresetsTv.text = componentLine(ComponentRepository.PRESETS)
    }

    private fun componentLine(name: String): String {
        val installed = ComponentStore.installedVersion(name, this)
        return if (installed != null) {
            getString(R.string.components_installed, installed)
        } else {
            getString(R.string.components_bundled)
        }
    }

    private fun displayName(name: String): String = when (name) {
        ComponentRepository.BYEDPI -> getString(R.string.components_byedpi)
        ComponentRepository.TGWS -> getString(R.string.components_tgws)
        ComponentRepository.PRESETS -> getString(R.string.components_presets)
        else -> name
    }

    private fun saveSourceIfValid(): Boolean {
        val url = binding.componentsUrlEt.text.toString().trim()
        if (!url.startsWith("https://")) {
            Toast.makeText(this, R.string.components_invalid_url, Toast.LENGTH_SHORT).show()
            return false
        }
        ComponentRepository.setManifestUrl(this, url)
        return true
    }

    private fun setBusy(busy: Boolean, status: String?) {
        binding.componentsCheckBtn.isEnabled = !busy
        binding.componentsUpdateBtn.isEnabled = !busy
        binding.componentsImportBtn.isEnabled = !busy
        binding.componentsResetBtn.isEnabled = !busy
        binding.componentsSaveUrlBtn.isEnabled = !busy
        if (status != null) binding.componentsStatusTv.text = status
    }
}
