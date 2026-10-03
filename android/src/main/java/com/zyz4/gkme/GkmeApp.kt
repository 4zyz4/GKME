package com.zyz4.gkme

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GkmeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppStartupTracker.markApplicationCreated()
    }
}
