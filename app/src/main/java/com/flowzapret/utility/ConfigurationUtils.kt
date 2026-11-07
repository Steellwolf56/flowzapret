package com.flowzapret.utility

import android.content.Context
import com.flowzapret.core.ByeDpiProxyCmdPreferences
import com.flowzapret.data.Configuration

fun testConfiguration(context: Context, command: String, host: String, port: Int): Configuration {
    val args = ByeDpiProxyCmdPreferences(context, command, host, port).args.toList()
    val (commandHost, commandPort) = args.checkIpAndPortInArgs()
    return Configuration(args, commandHost ?: host, commandPort?.toInt() ?: port)
}
