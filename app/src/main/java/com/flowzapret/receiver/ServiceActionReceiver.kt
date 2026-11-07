package com.flowzapret.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.flowzapret.data.Mode
import com.flowzapret.data.PAUSE_ACTION
import com.flowzapret.data.RESTART_ACTION
import com.flowzapret.data.RESUME_ACTION
import com.flowzapret.data.START_ACTION
import com.flowzapret.data.STOP_ACTION
import com.flowzapret.services.ServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ServiceActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ServiceActionReceiver"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PID = "pid"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != START_ACTION && action != RESTART_ACTION && action != RESUME_ACTION &&
            action != STOP_ACTION && action != PAUSE_ACTION) {
            return
        }
        val modeName = intent.getStringExtra(EXTRA_MODE)
        val mode = Mode.entries.firstOrNull { it.name == modeName } ?: return
        val pending = goAsync()

        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                ServiceManager.handleAction(context.applicationContext, action, mode, intent.getIntExtra(EXTRA_PID, 0))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle service action: $action", e)
            } finally {
                pending.finish()
            }
        }
    }
}
