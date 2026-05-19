package com.beyond.nvr

import android.app.Application
import com.beyond.nvr.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class GoNvrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@GoNvrApp)
            modules(appModule)
        }
    }
}
