package com.flowzapret.core

import com.flowzapret.components.NativeLibLoader

object TProxyService {
    init {
        NativeLibLoader.load("byedpi")
    }

    external fun startTunnel(config: String, fd: Int): Int

    external fun stopTunnel()
}
