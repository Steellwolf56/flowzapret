package com.flowzapret.services

import com.flowzapret.data.AppStatus
import com.flowzapret.data.Mode

var appStatus = AppStatus.Halted to Mode.VPN
    private set

fun setStatus(status: AppStatus, mode: Mode) {
    appStatus = status to mode
}
