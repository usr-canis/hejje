package com.canis.hejje

import android.app.Application
import kotlinx.coroutines.launch

class HejjeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        // If the app was killed or the phone rebooted mid-drive, pick the recording up again.
        container.controller.recover()
        container.scope.launch { container.log.prune() }
        container.scope.launch { container.upgradeStats() }
        container.scheduleWork()
    }
}
