package com.flowzapret.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.flowzapret.R
import com.flowzapret.databinding.PageTelegramBinding
import com.flowzapret.tgproxy.TgProxyController
import com.flowzapret.tgproxy.TgProxyNative
import com.flowzapret.tgproxy.TgProxyService
import com.flowzapret.tgproxy.TgProxySettings
import com.flowzapret.tgproxy.TgProxyState
import com.flowzapret.utility.ClipboardUtils
import kotlinx.coroutines.launch

/**
 * Вкладка «Telegram» — настройки и управление MTProto-прокси.
 */
class TelegramFragment : Fragment() {

    private var _binding: PageTelegramBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: TgProxySettings

    /** Защита от рекурсии при программной установке состояния переключателя. */
    private var updatingToggle = false

    private val dcFields: List<Pair<String, () -> android.widget.EditText>> get() = listOf(
        "dc1" to { binding.tgDc1Et },
        "dc2" to { binding.tgDc2Et },
        "dc3" to { binding.tgDc3Et },
        "dc4" to { binding.tgDc4Et },
        "dc5" to { binding.tgDc5Et },
        "dc203" to { binding.tgDc203Et },
        "dc1m" to { binding.tgDc1mEt },
        "dc2m" to { binding.tgDc2mEt },
        "dc3m" to { binding.tgDc3mEt },
        "dc4m" to { binding.tgDc4mEt },
        "dc5m" to { binding.tgDc5mEt },
        "dc203m" to { binding.tgDc203mEt },
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = PageTelegramBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = TgProxySettings(requireContext())

        bindListeners()
        loadFromSettings()
        updateAbiWarning()
        observeService()
    }

    private fun updateAbiWarning() {
        val available = TgProxyNative.isAvailable
        binding.tgAbiWarning.visibility = if (available) View.GONE else View.VISIBLE
        binding.tgApplyBtn.isEnabled = available
        binding.tgCopyBtn.isEnabled = available
        renderState(TgProxyService.state.value)
    }

    private fun bindListeners() {
        binding.tgToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingToggle) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.tg_btn_on -> {
                    val current = TgProxyService.state.value
                    if (current == TgProxyState.RUNNING || current == TgProxyState.STARTING) {
                        return@addOnButtonCheckedListener
                    }
                    if (!saveFromUi()) {
                        renderState(current)
                        return@addOnButtonCheckedListener
                    }
                    TgProxyController.start(requireContext())
                }

                R.id.tg_btn_off -> {
                    val current = TgProxyService.state.value
                    if (current == TgProxyState.IDLE || current == TgProxyState.STOPPING) {
                        return@addOnButtonCheckedListener
                    }
                    TgProxyController.stop(requireContext())
                }
            }
        }

        binding.tgSaveBtn.setOnClickListener {
            if (saveFromUi()) {
                val running = TgProxyService.state.value == TgProxyState.RUNNING
                val text = if (running) R.string.tg_saved_restart_needed else R.string.tg_saved
                Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
            }
        }

        binding.tgApplyBtn.setOnClickListener {
            if (!saveFromUi()) return@setOnClickListener
            TgProxyController.applyToTelegram(
                requireContext(),
                TgProxyController.buildProxyUrl(settings),
            )
        }

        binding.tgCopyBtn.setOnClickListener {
            if (!saveFromUi()) return@setOnClickListener
            ClipboardUtils.copy(
                requireContext(),
                TgProxyController.buildProxyUrl(settings),
                getString(R.string.tg_proxy_link),
            )
        }

        binding.tgSecretCopyBtn.setOnClickListener {
            ClipboardUtils.copy(
                requireContext(),
                binding.tgSecretEt.text.toString(),
                getString(R.string.tg_secret),
            )
        }

        binding.tgSecretRegenBtn.setOnClickListener {
            val old = settings.secretKey
            settings.secretKey = TgProxySettings.newSecret()
            showSecret()
            if (TgProxyService.isRunning.value && old != settings.secretKey) {
                Toast.makeText(requireContext(), R.string.tg_secret_regenerated, Toast.LENGTH_SHORT).show()
            }
        }

        binding.tgCustomCfCheckbox.setOnCheckedChangeListener { _, checked ->
            binding.tgCustomCfEt.isEnabled = checked && binding.tgCfCheckbox.isChecked
        }
        binding.tgCfCheckbox.setOnCheckedChangeListener { _, checked ->
            binding.tgCustomCfEt.isEnabled = checked && binding.tgCustomCfCheckbox.isChecked
        }

        binding.tgFakeTlsCheckbox.setOnCheckedChangeListener { _, checked ->
            binding.tgFakeTlsEt.isEnabled = checked
        }

        binding.tgDcAutoCheckbox.setOnCheckedChangeListener { _, checked ->
            binding.tgDcManualLayout.visibility = if (checked) View.GONE else View.VISIBLE
        }

        binding.tgExperimentalCheckbox.setOnCheckedChangeListener { _, checked ->
            val visibility = if (checked) View.VISIBLE else View.GONE
            binding.tgMediaDcLabel.visibility = visibility
            binding.tgMediaDcContainer.visibility = visibility
        }

        binding.tgOpenLogsBtn.setOnClickListener {
            startActivity(Intent(requireContext(), TgProxyLogActivity::class.java))
        }
    }

    private fun loadFromSettings() {
        binding.tgBindIpEt.setText(settings.bindIp)
        binding.tgPortEt.setText(settings.port)
        binding.tgPoolEt.setText(settings.poolSize.toString())
        binding.tgBufferEt.setText(settings.bufferSizeKb.toString())

        binding.tgCfCheckbox.isChecked = settings.cfEnabled
        binding.tgCustomCfCheckbox.isChecked = settings.customCfDomainEnabled
        binding.tgCustomCfEt.setText(settings.customCfDomain)
        binding.tgCustomCfEt.isEnabled = settings.cfEnabled && settings.customCfDomainEnabled

        binding.tgFakeTlsCheckbox.isChecked = settings.fakeTlsDomain.isNotBlank()
        binding.tgFakeTlsEt.setText(settings.fakeTlsDomain)
        binding.tgFakeTlsEt.isEnabled = settings.fakeTlsDomain.isNotBlank()

        binding.tgDisableSecureCheckbox.isChecked = settings.disableSecure
        binding.tgProtocolCheckbox.isChecked = settings.proxyProtocol
        binding.tgForceTestDcCheckbox.isChecked = settings.forceTestDc
        binding.tgAutostartCheckbox.isChecked = settings.autoStartOnBoot

        binding.tgDcAutoCheckbox.isChecked = settings.isDcAuto
        binding.tgDcManualLayout.visibility = if (settings.isDcAuto) View.GONE else View.VISIBLE

        binding.tgExperimentalCheckbox.isChecked = settings.isExperimentalMode
        val mediaVisibility = if (settings.isExperimentalMode) View.VISIBLE else View.GONE
        binding.tgMediaDcLabel.visibility = mediaVisibility
        binding.tgMediaDcContainer.visibility = mediaVisibility

        for ((key, field) in dcFields) {
            field().setText(settings.getDc(key))
        }

        showSecret()
    }

    private fun showSecret() {
        val secret = if (TgProxyService.isRunning.value) {
            TgProxyService.secretWithPrefix.value.ifBlank {
                TgProxyController.secretForUrl(settings)
            }
        } else {
            TgProxyController.secretForUrl(settings)
        }
        binding.tgSecretEt.setText(secret)
    }

    private fun saveFromUi(): Boolean {
        val port = binding.tgPortEt.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(requireContext(), R.string.tg_invalid_port, Toast.LENGTH_SHORT).show()
            return false
        }

        val pool = binding.tgPoolEt.text.toString().trim().toIntOrNull() ?: 4
        val buffer = binding.tgBufferEt.text.toString().trim().toIntOrNull() ?: 64
        val bindIp = binding.tgBindIpEt.text.toString().trim().ifBlank { "127.0.0.1" }

        settings.bindIp = bindIp
        settings.port = port.toString()
        settings.poolSize = pool.coerceIn(1, 64)
        settings.bufferSizeKb = buffer.coerceIn(16, 1024)

        settings.cfEnabled = binding.tgCfCheckbox.isChecked
        settings.customCfDomainEnabled = binding.tgCustomCfCheckbox.isChecked
        settings.customCfDomain = binding.tgCustomCfEt.text.toString()
        settings.fakeTlsDomain = if (binding.tgFakeTlsCheckbox.isChecked) {
            binding.tgFakeTlsEt.text.toString()
        } else {
            ""
        }
        settings.disableSecure = binding.tgDisableSecureCheckbox.isChecked
        settings.proxyProtocol = binding.tgProtocolCheckbox.isChecked
        settings.forceTestDc = binding.tgForceTestDcCheckbox.isChecked
        settings.autoStartOnBoot = binding.tgAutostartCheckbox.isChecked
        settings.isDcAuto = binding.tgDcAutoCheckbox.isChecked
        settings.isExperimentalMode = binding.tgExperimentalCheckbox.isChecked

        for ((key, field) in dcFields) {
            settings.setDc(key, field().text.toString())
        }

        settings.ensureSecret()
        showSecret()
        return true
    }

    private fun renderState(state: TgProxyState) {
        val available = TgProxyNative.isAvailable
        val busy = state == TgProxyState.STARTING || state == TgProxyState.STOPPING

        updatingToggle = true
        val target = if (state == TgProxyState.RUNNING || state == TgProxyState.STARTING) {
            R.id.tg_btn_on
        } else {
            R.id.tg_btn_off
        }
        if (binding.tgToggleGroup.checkedButtonId != target) {
            binding.tgToggleGroup.check(target)
        }
        updatingToggle = false

        binding.tgBtnOn.isEnabled = available && !busy
        binding.tgBtnOff.isEnabled = available && !busy

        binding.tgStatusTv.text = when (state) {
            TgProxyState.IDLE -> getString(R.string.tg_state_off)
            TgProxyState.STARTING -> getString(R.string.tg_state_starting)
            TgProxyState.STOPPING -> getString(R.string.tg_state_stopping)
            TgProxyState.RUNNING -> {
                val host = settings.bindIp.ifBlank { "127.0.0.1" }
                val port = settings.port.toIntOrNull() ?: 1443
                getString(R.string.tg_state_running, "$host:$port")
            }
        }
    }

    private fun observeService() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    TgProxyService.state.collect { state ->
                        renderState(state)
                        showSecret()
                    }
                }
                launch {
                    TgProxyService.stats.collect { stats ->
                        binding.tgStatsTv.text = if (stats.isBlank()) {
                            getString(R.string.tg_traffic_0)
                        } else {
                            getString(R.string.tg_traffic, stats)
                        }
                    }
                }
                launch {
                    TgProxyService.secretWithPrefix.collect { showSecret() }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
