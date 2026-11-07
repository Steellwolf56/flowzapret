package com.flowzapret

import android.app.Application
import android.content.Context

/**
 * Точка входа приложения. Хранит application context для загрузчиков,
 * которым он нужен вне Activity/Service (например, NativeLibLoader).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        private lateinit var instance: App

        fun context(): Context = instance.applicationContext
    }
}
