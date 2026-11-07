package com.flowzapret.core

import com.flowzapret.components.NativeLibLoader

class ByeDpiProxy {
    companion object {
        init {
            NativeLibLoader.load("byedpi")
        }
    }

    fun startProxy(args: Array<String>): Int = jniStartProxy(args)

    fun stopProxy(): Int {
        return jniStopProxy()
    }

    private external fun jniStartProxy(args: Array<String>): Int
    private external fun jniStopProxy(): Int
}
