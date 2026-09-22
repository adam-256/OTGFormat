package dev.otgformat.app

import android.app.Application

/**
 * Exists only to install the crash handler before anything else runs.
 */
class OtgFormatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
