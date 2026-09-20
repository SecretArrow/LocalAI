package com.localai.runtime

import android.app.Application
import com.localai.runtime.runtime.AppContainer

class LocalAiApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
