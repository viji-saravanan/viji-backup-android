package com.aryasubramani.vijibackup.app

import android.app.Application
import androidx.annotation.VisibleForTesting

class VijiBackupApplication : Application() {
    private val defaultAppContainer: AppContainer by lazy {
        DefaultAppContainer(this)
    }

    @VisibleForTesting
    internal var testAppContainer: AppContainer? = null

    internal val appContainer: AppContainer
        get() = testAppContainer ?: defaultAppContainer
}
