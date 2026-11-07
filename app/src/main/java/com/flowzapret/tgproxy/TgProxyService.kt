package com.flowzapret.tgproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.flowzapret.R
import com.flowzapret.activities.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Foreground-сервис Telegram MTProto прокси.
 *
 * Все команды (старт/стоп/рестарт) проходят через одну последовательную
 * очередь, поэтому StartProxy/StopProxy никогда не пересекаются, а быстрый
 * «стоп → старт» не теряет запуск. Любая команда несёт полный снимок
 * настроек [TgProxyConfig].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TgProxyService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var statsJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val commands = Channel<Command>(Channel.UNLIMITED)

    private var config = TgProxyConfig()
    private var terminateRequested = false

    @Volatile
    private var stopRequested = false

    private sealed interface Command {
        data class Start(val config: TgProxyConfig) : Command
        data class Restart(val config: TgProxyConfig) : Command
        data object Stop : Command
    }

    companion object {
        private const val TAG = "TgProxyService"

        const val ACTION_START = "com.flowzapret.tgproxy.START"
        const val ACTION_STOP = "com.flowzapret.tgproxy.STOP"
        const val ACTION_RESTART = "com.flowzapret.tgproxy.RESTART"

        const val EXTRA_BIND_IP = "EXTRA_BIND_IP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_IPS = "EXTRA_IPS"
        const val EXTRA_POOL_SIZE = "EXTRA_POOL_SIZE"
        const val EXTRA_BUFFER_SIZE = "EXTRA_BUFFER_SIZE"
        const val EXTRA_CF_ENABLED = "EXTRA_CF_ENABLED"
        const val EXTRA_CF_DOMAIN = "EXTRA_CF_DOMAIN"
        const val EXTRA_FAKE_TLS_DOMAIN = "EXTRA_FAKE_TLS_DOMAIN"
        const val EXTRA_DISABLE_SECURE = "EXTRA_DISABLE_SECURE"
        const val EXTRA_FORCE_TEST_DC = "EXTRA_FORCE_TEST_DC"
        const val EXTRA_PROXY_PROTOCOL = "EXTRA_PROXY_PROTOCOL"
        const val EXTRA_SECRET = "EXTRA_SECRET"

        const val NOTIFICATION_ID = 102
        private const val CHANNEL_ID = "TgProxy_Service"
        private const val WAKELOCK_TIMEOUT_MS = 30L * 60 * 1000
        private const val WAKELOCK_REFRESH_MS = 25L * 60 * 1000
        private const val STATS_UPDATE_MS = 3_000L

        private val _state = MutableStateFlow(TgProxyState.IDLE)
        val state: StateFlow<TgProxyState> = _state

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _stats = MutableStateFlow("")
        val stats: StateFlow<String> = _stats

        private val _secretWithPrefix = MutableStateFlow("")
        val secretWithPrefix: StateFlow<String> = _secretWithPrefix
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch { commandLoop() }
    }

    private suspend fun commandLoop() {
        for (command in commands) {
            when (command) {
                is Command.Start -> {
                    if (!doStart(command.config)) terminateRequested = true
                }

                is Command.Restart -> {
                    doStop()
                    if (!doStart(command.config)) terminateRequested = true
                }

                Command.Stop -> {
                    doStop()
                    stopRequested = true
                }
            }

            if (terminateRequested || (stopRequested && commands.isEmpty)) {
                terminateRequested = false
                stopRequested = false
                terminate()
                break
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                TgProxyLogManager.clear()
                TgProxyLogManager.ensureStarted()
                val cfg = configFrom(intent)
                ensureForeground(getString(R.string.tg_notification_starting))
                commands.trySend(Command.Start(cfg))
            }

            ACTION_STOP -> commands.trySend(Command.Stop)

            ACTION_RESTART -> {
                val cfg = configFrom(intent)
                ensureForeground(getString(R.string.tg_notification_starting))
                commands.trySend(Command.Restart(cfg))
            }

            null -> {
                // Сервис пересоздан системой: поднимаем прокси только если
                // пользователь его не выключал.
                val settings = TgProxySettings(this)
                if (settings.shouldRun) {
                    Log.w(TAG, "Service re-created by system, restoring proxy")
                    ensureForeground(getString(R.string.tg_notification_starting))
                    commands.trySend(Command.Start(settings.toConfig()))
                } else {
                    stopSelf()
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    /**
     * Если intent пришёл с полным набором extras — берём их.
     * Иначе (например, старый PendingIntent без extras) читаем сохранённые
     * настройки, а не сбрасываем всё в дефолты.
     */
    private fun configFrom(intent: Intent): TgProxyConfig {
        if (!intent.hasExtra(EXTRA_PORT)) {
            return TgProxySettings(this).toConfig()
        }
        return TgProxyConfig(
            bindIp = intent.getStringExtra(EXTRA_BIND_IP) ?: "127.0.0.1",
            port = intent.getIntExtra(EXTRA_PORT, 1443),
            dcIps = intent.getStringExtra(EXTRA_IPS) ?: "",
            poolSize = intent.getIntExtra(EXTRA_POOL_SIZE, 4),
            bufferSizeKb = intent.getIntExtra(EXTRA_BUFFER_SIZE, 64),
            cfEnabled = intent.getBooleanExtra(EXTRA_CF_ENABLED, true),
            cfDomain = intent.getStringExtra(EXTRA_CF_DOMAIN) ?: "",
            fakeTlsDomain = intent.getStringExtra(EXTRA_FAKE_TLS_DOMAIN) ?: "",
            disableSecure = intent.getBooleanExtra(EXTRA_DISABLE_SECURE, true),
            forceTestDc = intent.getBooleanExtra(EXTRA_FORCE_TEST_DC, false),
            proxyProtocol = intent.getBooleanExtra(EXTRA_PROXY_PROTOCOL, false),
            secret = intent.getStringExtra(EXTRA_SECRET) ?: "",
        )
    }

    private suspend fun doStart(cfg: TgProxyConfig): Boolean {
        if (_state.value == TgProxyState.RUNNING && config == cfg) return true

        config = cfg
        _state.value = TgProxyState.STARTING
        ensureForeground(getString(R.string.tg_notification_starting))
        acquireWakeLock()

        // Предыдущий экземпляр ядра мог остаться живым (рестарт).
        if (_isRunning.value) {
            withContext(Dispatchers.IO) { runCatching { TgProxyNative.stopProxy() } }
            _isRunning.value = false
        }

        val result = withContext(Dispatchers.IO) {
            try {
                if (!isPortAvailable()) {
                    return@withContext -2
                }
                TgProxyNative.setPoolSize(cfg.poolSize)
                TgProxyNative.setBufferSizeKb(cfg.bufferSizeKb)
                TgProxyNative.setCfProxyCacheDir(cacheDir.absolutePath)
                TgProxyNative.setCfProxyConfig(cfg.cfEnabled, true, cfg.cfDomain)
                TgProxyNative.setFakeTlsDomain(cfg.fakeTlsDomain)
                TgProxyNative.setDisableSecure(cfg.disableSecure)
                TgProxyNative.setForceTestDc(cfg.forceTestDc)
                TgProxyNative.setProxyProtocol(cfg.proxyProtocol)
                TgProxyNative.startProxy(cfg.bindIp, cfg.port, cfg.dcIps, cfg.secret, true)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start native proxy", t)
                -3
            }
        }

        return if (result == 0) {
            _secretWithPrefix.value = TgProxyNative.getSecretWithPrefix() ?: ""
            _isRunning.value = true
            _state.value = TgProxyState.RUNNING
            TgProxySettings(this).shouldRun = true
            updateNotification(getString(R.string.tg_notification_running))
            Log.i(TAG, "MTProto proxy ready on ${cfg.bindIp}:${cfg.port}")
            startStatsLoop()
            true
        } else {
            _isRunning.value = false
            _state.value = TgProxyState.IDLE
            releaseWakeLock()
            val message = when (result) {
                -2 -> getString(R.string.tg_port_busy, cfg.port)
                -3 -> getString(R.string.tg_start_failed, "")
                else -> getString(R.string.tg_start_error, result)
            }
            notifyError(message)
            false
        }
    }

    private suspend fun doStop() {
        if (_state.value == TgProxyState.IDLE && !_isRunning.value) {
            TgProxySettings(this).shouldRun = false
            return
        }

        _state.value = TgProxyState.STOPPING
        statsJob?.cancel()
        statsJob = null
        updateNotification(getString(R.string.tg_notification_stopping))

        withContext(Dispatchers.IO) { runCatching { TgProxyNative.stopProxy() } }

        releaseWakeLock()
        _isRunning.value = false
        _stats.value = ""
        _state.value = TgProxyState.IDLE
        TgProxySettings(this).shouldRun = false
    }

    private fun terminate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            launch {
                while (isActive) {
                    delay(WAKELOCK_REFRESH_MS)
                    refreshWakeLock()
                }
            }
            while (isActive) {
                delay(STATS_UPDATE_MS)
                if (_state.value != TgProxyState.RUNNING) continue
                try {
                    val raw = TgProxyNative.getStats() ?: continue
                    _stats.value = raw
                    updateNotification(getString(R.string.tg_notification_traffic, raw))
                } catch (t: Throwable) {
                    Log.w(TAG, "Stats update failed", t)
                }
            }
        }
    }

    private fun isPortAvailable(): Boolean = try {
        ServerSocket().use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(InetAddress.getByName(config.bindIp), config.port))
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun notifyError(text: String) {
        Log.e(TAG, text)
        updateNotification(text)
    }

    private fun ensureForeground(text: String) {
        try {
            val notification = buildNotification(text)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to enter foreground", t)
        }
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to update notification", t)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tg_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_TAB, 1)
        }
        val openPending = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Полный снимок сохранённых настроек: рестарт из шторки больше не
        // сбрасывает Fake TLS / PROXY protocol / порт.
        val restartIntent = TgProxyController.buildIntent(
            this,
            ACTION_RESTART,
            TgProxySettings(this).toConfig(),
        )
        val restartPending = PendingIntent.getService(
            this, 2, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this, 3,
            Intent(this, TgProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tg_notification_title))
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_popup_sync, getString(R.string.tg_notification_restart), restartPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.tg_notification_stop), stopPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlowZapret::TgProxy").apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to acquire wake lock", t)
        }
    }

    private fun refreshWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            acquireWakeLock()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to refresh wake lock", t)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release wake lock", t)
        }
        wakeLock = null
    }

    override fun onDestroy() {
        statsJob?.cancel()
        statsJob = null
        releaseWakeLock()
        _isRunning.value = false
        _stats.value = ""
        _state.value = TgProxyState.IDLE
        commands.close()
        scope.cancel()
        runCatching { TgProxyNative.stopProxy() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
