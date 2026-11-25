package io.github.kotbctakahe.hys1link

import android.app.Application
import io.github.kotbctakahe.hys1link.core.Core

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.get(this)
    }
}
