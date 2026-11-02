package com.example.focaaa

import android.app.Application

/** Installs the build-appropriate Firebase App Check provider before callable use. */
class FocaaaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FocaaaAppCheckInitializer.install(this)
    }
}
