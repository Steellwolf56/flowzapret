package com.flowzapret.activities

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.DynamicColors
import com.flowzapret.R
import com.flowzapret.utility.SettingsUtils
import com.flowzapret.utility.getPreferences
import com.flowzapret.utility.getStringNotNull

abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getPreferences()

        val lang = prefs.getStringNotNull("language", "system")
        SettingsUtils.setLang(lang)

        val theme = prefs.getStringNotNull("app_theme", "system")
        SettingsUtils.setTheme(theme)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivityIfAvailable(this)
        }

        super.onCreate(savedInstanceState)
    }

    protected fun setupToolbar() {
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
    }

}
