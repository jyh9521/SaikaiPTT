package com.saikai.ptt

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether any of this app's screens is currently on display.
 *
 * Exists for one platform rule. Android 14 forbids promoting a foreground
 * service to the `microphone` type from the background, so transmitting
 * requires a visible UI while receiving does not (ADR-005 sections 2 and 3,
 * `docs/01_PRD.md` section 10.5). Asking first turns that into a clear refusal
 * -- "open the app to talk" -- instead of an exception at the moment the user
 * presses the button.
 *
 * Counts started Activities rather than resumed ones. Started is the window in
 * which the platform considers the app foreground for this purpose, and it is
 * also the window that survives a permission dialog or the notification shade
 * being pulled down over the talk button.
 *
 * Application scope, and registered from [SaikaiApplication], because the
 * service asks the question and the service outlives every Activity.
 * `ActivityLifecycleCallbacks` rather than an observer the UI has to remember to
 * update: a screen added later cannot forget to take part.
 */
class AppVisibility : Application.ActivityLifecycleCallbacks {

    private val started = AtomicInteger(0)

    private val _foreground = MutableStateFlow(false)

    /**
     * The same answer, as something that can be watched.
     *
     * The talk button asks the question once, at the moment of a press, and
     * [isForeground] is enough for that. The floating indicator has to *react*:
     * it exists to say what is happening while no screen of this app is
     * showing, so it has to be told when that stops being true (`docs/04_UI_UX.md`
     * section 18).
     */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    /** True while at least one Activity is between onStart and onStop. */
    val isForeground: Boolean get() = started.get() > 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        _foreground.value = started.incrementAndGet() > 0
    }

    override fun onActivityStopped(activity: Activity) {
        _foreground.value = started.updateAndGet { if (it > 0) it - 1 else 0 } > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
