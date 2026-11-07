package com.flowzapret.activities

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.flowzapret.R
import com.flowzapret.data.AppStatus
import com.flowzapret.data.Command
import com.flowzapret.data.FAILED_BROADCAST
import com.flowzapret.data.Mode
import com.flowzapret.data.SENDER
import com.flowzapret.data.STARTED_BROADCAST
import com.flowzapret.data.STOPPED_BROADCAST
import com.flowzapret.data.Sender
import com.flowzapret.databinding.PageBypassBinding
import com.flowzapret.services.ServiceManager
import com.flowzapret.services.TestService
import com.flowzapret.services.appStatus
import com.flowzapret.utility.HistoryUtils
import com.flowzapret.utility.PermissionUtils
import com.flowzapret.utility.ShortcutUtils
import com.flowzapret.utility.getCmdArgs
import com.flowzapret.utility.getCmdEnable
import com.flowzapret.utility.getPreferences
import com.flowzapret.utility.getProxyIpAndPort
import com.flowzapret.utility.mode

/**
 * Вкладка «Обход DPI» — прежний главный экран ByeByeDPI/ByeDPI.
 */
class PageBypassFragment : Fragment() {

    private var _binding: PageBypassBinding? = null
    private val binding get() = _binding!!

    private lateinit var historyUtils: HistoryUtils
    private var receiverRegistered = false

    companion object {
        private val TAG: String = PageBypassFragment::class.java.simpleName
        private const val BATTERY_OPTIMIZATION_REQUESTED = "battery_optimization_requested"
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == android.app.Activity.RESULT_OK) {
                ServiceManager.start(requireContext(), Mode.VPN)
            } else {
                Toast.makeText(requireContext(), R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestBatteryOptimization()
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            Log.d(TAG, "Received intent: ${intent.action}")

            when (intent.action) {
                STARTED_BROADCAST, STOPPED_BROADCAST -> updateStatus()

                FAILED_BROADCAST -> {
                    val senderOrdinal = intent.getIntExtra(SENDER, -1)
                    val sender = Sender.entries.getOrNull(senderOrdinal)?.name ?: ""
                    Toast.makeText(
                        context ?: requireContext(),
                        getString(R.string.failed_to_start, sender),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = PageBypassBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        historyUtils = HistoryUtils(requireContext())

        binding.statusButtonCard.setOnClickListener {
            binding.statusButtonCard.isClickable = false

            when (appStatus.first) {
                AppStatus.Halted -> start()
                AppStatus.Running -> stop()
            }

            binding.statusButtonCard.postDelayed({
                _binding?.statusButtonCard?.isClickable = true
            }, 1000)
        }

        binding.statusButtonCard.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.statusButtonCard.strokeWidth = 10
                binding.statusButtonCard.strokeColor = android.graphics.Color.argb(100, 0, 0, 0)
            } else {
                binding.statusButtonCard.strokeWidth = 0
            }
        }

        binding.settingsButton.setOnClickListener {
            if (TestService.isRunning) {
                Toast.makeText(requireContext(), R.string.test_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (appStatus.first == AppStatus.Running) {
                Toast.makeText(requireContext(), R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        binding.editorButton.setOnClickListener {
            val useCmdSettings = requireContext().getPreferences().getCmdEnable()
            if (!useCmdSettings && appStatus.first == AppStatus.Running) {
                Toast.makeText(requireContext(), R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            intent.putExtra("open_fragment", if (useCmdSettings) "cmd" else "ui")
            startActivity(intent)
        }

        binding.testProxyButton.setOnClickListener {
            startActivity(Intent(requireContext(), TestActivity::class.java))
        }

        binding.domainListsButton.setOnClickListener {
            val intent = Intent(requireContext(), TestSettingsActivity::class.java)
            intent.putExtra("open_fragment", "domain_lists")
            startActivity(intent)
        }

        binding.strategyButton.setOnClickListener { showStrategyPicker() }

        if (savedInstanceState == null) {
            if (!PermissionUtils.hasNotificationPermission(requireContext()) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                requestBatteryOptimization()
            }

            if (!TestService.isRunning &&
                requireContext().getPreferences().getBoolean("auto_connect", false) &&
                appStatus.first != AppStatus.Running
            ) {
                start()
            }

            ShortcutUtils.update(requireContext())
        }
    }

    override fun onStart() {
        super.onStart()
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(STARTED_BROADCAST)
                addAction(STOPPED_BROADCAST)
                addAction(FAILED_BROADCAST)
            }
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                requireContext().registerReceiver(receiver, filter)
            }
            receiverRegistered = true
        }
    }

    override fun onResume() {
        super.onResume()
        ServiceManager.refresh(requireContext())
        updateStatus()
        updateStrategyButton()
    }

    override fun onStop() {
        if (receiverRegistered) {
            try {
                requireContext().unregisterReceiver(receiver)
            } catch (_: Exception) {
                // ignore
            }
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun start() {
        if (TestService.isRunning) {
            Toast.makeText(requireContext(), R.string.test_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        when (requireContext().getPreferences().mode()) {
            Mode.VPN -> {
                val prepare = VpnService.prepare(requireContext())
                if (prepare != null) {
                    vpnRegister.launch(prepare)
                } else {
                    ServiceManager.start(requireContext(), Mode.VPN)
                }
            }

            Mode.Proxy -> ServiceManager.start(requireContext(), Mode.Proxy)
        }
    }

    private fun stop() {
        ServiceManager.stop()
    }

    private fun updateStatus() {
        val b = _binding ?: return
        val (status, mode) = appStatus
        val preferences = requireContext().getPreferences()
        val (ip, port) = preferences.getProxyIpAndPort()
        b.proxyAddress.text = getString(R.string.proxy_address, ip, port)

        when (status) {
            AppStatus.Halted -> {
                val typedValue = android.util.TypedValue()
                requireContext().theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)
                b.statusButtonCard.setCardBackgroundColor(typedValue.data)
                b.statusButtonIcon.clearColorFilter()
                b.statusText.setText(
                    when (preferences.mode()) {
                        Mode.VPN -> R.string.vpn_disconnected
                        Mode.Proxy -> R.string.proxy_down
                    }
                )
            }

            AppStatus.Running -> {
                b.statusButtonCard.setCardBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.green_active)
                )
                b.statusButtonIcon.setColorFilter(
                    ContextCompat.getColor(requireContext(), android.R.color.white)
                )
                b.statusText.setText(
                    when (mode) {
                        Mode.VPN -> R.string.vpn_connected
                        Mode.Proxy -> R.string.proxy_up
                    }
                )
            }
        }
    }

    private fun updateStrategyButton() {
        val b = _binding ?: return
        val useCmdSettings = requireContext().getPreferences().getCmdEnable()

        if (!useCmdSettings) {
            b.cmdButtonsRow.visibility = View.GONE
            b.strategyButton.visibility = View.GONE
            return
        }

        b.cmdButtonsRow.visibility = View.VISIBLE

        val pinned = historyUtils.getPinnedHistory()
        val currentCmdArgs = requireContext().getPreferences().getCmdArgs()
        val matched = pinned.find { it.text == currentCmdArgs }
        val name = matched?.name?.takeIf { it.isNotBlank() }

        if (name != null) {
            b.strategyButtonName.text = name
            b.strategyButtonName.visibility = View.VISIBLE
            b.strategyButtonText.maxLines = 1
        } else {
            b.strategyButtonName.visibility = View.GONE
            b.strategyButtonText.maxLines = 2
        }

        b.strategyButtonText.text =
            currentCmdArgs.ifBlank { getString(R.string.main_strategy_picker) }
        b.strategyButton.visibility = View.VISIBLE
    }

    private fun showStrategyPicker() {
        val pinned = historyUtils.getPinnedHistory()
        if (pinned.isEmpty()) {
            Toast.makeText(requireContext(), R.string.main_strategy_no_pinned, Toast.LENGTH_SHORT).show()
            return
        }

        val adapter = object : ArrayAdapter<Command>(
            requireContext(),
            R.layout.item_main_strategy,
            pinned,
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: layoutInflater.inflate(
                    R.layout.item_main_strategy,
                    parent,
                    false,
                )
                val command = getItem(position)!!
                val nameView = view.findViewById<TextView>(R.id.strategyName)
                val textView = view.findViewById<TextView>(R.id.strategyText)
                val dividerView = view.findViewById<View>(R.id.strategyDivider)

                val name = command.name?.takeIf { it.isNotBlank() }
                if (name != null) {
                    nameView.visibility = View.VISIBLE
                    nameView.text = name
                    textView.maxLines = 2
                } else {
                    nameView.visibility = View.GONE
                    textView.maxLines = 3
                }
                textView.text = command.text
                dividerView.visibility = if (position == count - 1) View.GONE else View.VISIBLE
                return view
            }
        }

        val listView = ListView(requireContext()).apply {
            divider = null
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
            this.adapter = adapter
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.main_strategy_picker))
            .setView(listView)
            .setNegativeButton(getString(android.R.string.cancel), null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            applyStrategy(pinned[position].text)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun applyStrategy(commandText: String) {
        requireContext().getPreferences().edit { putString("byedpi_cmd_args", commandText) }
        updateStrategyButton()

        if (appStatus.first == AppStatus.Running) {
            val mode = requireContext().getPreferences().mode()
            if (mode == Mode.VPN && VpnService.prepare(requireContext()) != null) return
            ServiceManager.restart(requireContext(), mode)
            Toast.makeText(requireContext(), R.string.service_restart, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestBatteryOptimization() {
        val preferences = requireContext().getPreferences()
        val alreadyRequested = preferences.getBoolean(BATTERY_OPTIMIZATION_REQUESTED, false)
        if (!alreadyRequested && !PermissionUtils.isBatteryOptimizationDisabled(requireContext())) {
            PermissionUtils.requestBatteryOptimization(requireContext())
            preferences.edit { putBoolean(BATTERY_OPTIMIZATION_REQUESTED, true) }
        }
    }
}
