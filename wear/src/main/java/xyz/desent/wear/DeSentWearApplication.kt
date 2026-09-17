package xyz.desent.wear

import android.app.Application

class DeSentWearApplication : Application() {

    companion object {
        lateinit var appContainer: WearAppContainer
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContainer = WearAppContainer.getInstance(this)
    }
}
