package dev.browserrestrictor.retro

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dev.browserrestrictor.retro.monitoring.DeviceAccessMonitor

class RestrictorApplication : Application() {
    lateinit var container: AppContainer
        private set

    private lateinit var accessMonitor: DeviceAccessMonitor

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        accessMonitor = DeviceAccessMonitor(this, container)
        ProcessLifecycleOwner.get().lifecycle.addObserver(accessMonitor)
    }
}

val android.content.Context.appContainer: AppContainer
    get() = (applicationContext as RestrictorApplication).container
