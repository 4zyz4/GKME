package com.zyz4.gkme

import android.app.Application
import android.content.Context
import com.zyz4.gkme.i18n.AppLocale
import com.zyz4.gkme.i18n.AppStrings
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GkmeApp : Application() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        AppStrings.init(this)
        AppStartupTracker.markApplicationCreated()
    }
}
