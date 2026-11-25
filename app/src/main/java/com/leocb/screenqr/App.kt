package com.leocb.screenqr

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.leocb.screenqr.util.Notifications

/**
 * Holds process-wide state. The main job is tracking whether any of our activities is
 * currently resumed: when we are in the background we cannot start activities (Android 10+
 * background activity launch restrictions), so results are delivered as notifications instead.
 */
class App : Application(), Application.ActivityLifecycleCallbacks {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        com.leocb.screenqr.util.AppLocales.applyDefaultLocale()
        Notifications.ensureChannels(this)
        registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedCount++
    }

    override fun onActivityPaused(activity: Activity) {
        resumedCount = (resumedCount - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        lateinit var instance: App
            private set

        @Volatile
        private var resumedCount = 0

        val isInForeground: Boolean
            get() = resumedCount > 0
    }
}
