package com.nexora.hammerscale

import android.os.Bundle

class App : android.app.Application() {

    companion object {
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
