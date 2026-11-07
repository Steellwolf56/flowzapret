package com.flowzapret.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import com.flowzapret.data.Mode
import com.flowzapret.data.START_ACTION
import com.flowzapret.services.ServiceManager
import com.flowzapret.tgproxy.TgProxyController
import com.flowzapret.tgproxy.TgProxySettings
import com.flowzapret.utility.getPreferences
import com.flowzapret.utility.mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_REBOOT ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            // for A15, todo: use wasForceStopped
            if (SystemClock.elapsedRealtime() > 5 * 60 * 1000) {
                return
            }

            val preferences = context.getPreferences()
            val autorunEnabled = preferences.getBoolean("autostart", false)

            if(autorunEnabled) {
                val mode = preferences.mode()
                if (mode == Mode.VPN && VpnService.prepare(context) != null) return
                val pending = goAsync()

                CoroutineScope(Dispatchers.Main.immediate).launch {
                    try {
                        ServiceManager.handleAction(context.applicationContext, START_ACTION, mode)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start service after boot", e)
                    } finally {
                        pending.finish()
                    }
                }
            }

            try {
                val tgSettings = TgProxySettings(context)
                if (tgSettings.autoStartOnBoot) {
                    TgProxyController.start(context)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Telegram proxy after boot", e)
            }
        }
    }
}
