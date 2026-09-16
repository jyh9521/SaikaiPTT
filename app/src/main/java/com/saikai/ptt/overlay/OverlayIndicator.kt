package com.saikai.ptt.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.saikai.ptt.R
import com.saikai.ptt.core.common.LifecycleStep
import com.saikai.ptt.core.logger.LogCategory
import com.saikai.ptt.core.logger.Logger
import com.saikai.ptt.locale.AppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The floating indicator: green while nothing is happening, red with the
 * speaker's name while a transmission is playing (`docs/04_UI_UX.md` sections
 * 14, 18.1 and 19).
 *
 * ### It holds no communication state
 *
 * [onReceivingChanged] is pushed to it from the one place that watches the
 * session machine, on the edges only. Nothing here subscribes to the session,
 * asks the session anything, or remembers anything about it beyond the string
 * currently on screen. That is the requirement, and it is also what makes the
 * window cheap: the indicator changes twice a conversation.
 *
 * ### One window, and only when it can say something the app cannot
 *
 * A single `WindowManager` view, added and removed rather than kept around
 * hidden. It is shown when all three of these hold:
 *
 *  - the user turned it on,
 *  - the system permission is granted (checked on every attach, because it can
 *    be revoked while the service runs), and
 *  - **no screen of this app is on display**.
 *
 * The third is the one worth explaining. The indicator exists to say what is
 * happening when the app is not on screen; floating it over the app's own Home
 * screen, which already names the speaker, would be the same sentence twice in
 * two places -- and the two would be a frame out of step with each other.
 *
 * ### No animation
 *
 * Section 19 permits a bounded blink and forbids a continuous one. This does
 * neither: it changes colour and text once when a transmission starts and once
 * when it ends. A blink is an attention-grab for something the user has already
 * been given a louder one for -- the voice is playing out of the speaker -- and
 * the acceptance criterion for this task is that a long background run costs no
 * observable CPU. Nothing here redraws unless the session state changes.
 *
 * @param service the window's context. Wrapped for the chosen language on
 *   API 30-32, where nothing else has done it.
 */
class OverlayIndicator(
    private val service: Service,
    /** Whether the user wants it at all. */
    private val enabled: Flow<Boolean>,
    /** Whether any screen of this app is showing. */
    private val appForeground: Flow<Boolean>,
    private val logger: Logger,
    private val scope: CoroutineScope,
) : LifecycleStep {

    override val name: String = "overlay"

    /** The peer being listened to, or null. Pushed in; never read from elsewhere. */
    private val receivingFrom = MutableStateFlow<String?>(null)

    private var collector: Job? = null

    // Touched only on the main thread, which every path below enforces.
    private var windowManager: WindowManager? = null

    // Volatile because [isShowing] is read from the session collector, which
    // runs on a background dispatcher, to decide whether the notification has
    // to speak instead.
    @Volatile
    private var root: LinearLayout? = null
    private var dot: View? = null
    private var label: TextView? = null
    private var layout: WindowManager.LayoutParams? = null

    override suspend fun start() {
        collector = scope.launch(Dispatchers.Main.immediate) {
            combine(enabled, appForeground, receivingFrom) { on, foreground, peer ->
                Desired(visible = on && !foreground, peerName = peer)
            }
                .distinctUntilChanged()
                .collect(::apply)
        }
        logger.i(LogCategory.OVERLAY) { "overlay indicator watching" }
    }

    override suspend fun stop() {
        collector?.cancel()
        collector = null
        // NonCancellable because this runs during shutdown and a half-removed
        // window is a window the user cannot get rid of without rebooting.
        withContext(Dispatchers.Main.immediate + NonCancellable) { detach() }
        receivingFrom.value = null
    }

    /**
     * Told, from the single session collector, who is speaking -- or that
     * nobody is.
     *
     * Safe from any thread: it sets a flow value, and the collector that acts
     * on it runs on the main thread.
     */
    fun onReceivingChanged(peerName: String?) {
        receivingFrom.value = peerName
    }

    /**
     * Whether the indicator would currently be on screen.
     *
     * Read by the service to decide whether the ongoing notification has to
     * carry the receiving text instead (`docs/04_UI_UX.md` section 18.2: the
     * notification is the *fallback*, and two things saying the same thing is
     * worse than one).
     */
    val isShowing: Boolean get() = root != null

    private data class Desired(val visible: Boolean, val peerName: String?)

    private fun apply(desired: Desired) {
        if (!desired.visible) {
            detach()
            return
        }
        if (!attach()) return
        render(desired.peerName)
    }

    /** @return true when a window is up. */
    private fun attach(): Boolean {
        if (root != null) return true

        // Checked here rather than once at start-up: the user can revoke it from
        // system settings while the service is running, and the first sign of
        // that would otherwise be the exception below.
        if (!Settings.canDrawOverlays(service)) {
            logger.i(LogCategory.OVERLAY) { "overlay not shown: permission not granted" }
            return false
        }

        val manager = service.getSystemService(WindowManager::class.java) ?: return false
        val context = AppLocale.wrap(service)
        val view = buildView(context)
        val params = buildLayout()

        return try {
            manager.addView(view, params)
            windowManager = manager
            root = view
            layout = params
            true
        } catch (_: WindowManager.BadTokenException) {
            // The permission was granted a moment ago and is not now, or the ROM
            // refuses the window type. Neither is worth a crash: the notification
            // is already saying the same thing.
            logger.w(LogCategory.OVERLAY) { "overlay refused by the window manager" }
            clearViews()
            false
        } catch (error: SecurityException) {
            logger.w(LogCategory.OVERLAY) { "overlay refused: ${error.message}" }
            clearViews()
            false
        }
    }

    private fun detach() {
        val manager = windowManager
        val view = root
        if (manager != null && view != null) {
            try {
                manager.removeView(view)
            } catch (_: IllegalArgumentException) {
                // Already gone. Removing twice is not worth a crash on the way
                // out of a service that is stopping anyway.
            }
        }
        clearViews()
    }

    private fun clearViews() {
        windowManager = null
        root = null
        dot = null
        label = null
        layout = null
    }

    /**
     * Green with the app's name, or red with the speaker's.
     *
     * The text changing is what makes the state readable without colour
     * (section 3): one says what the app is, the other says who is talking. A
     * greyscale screenshot of the two is still two different things.
     */
    private fun render(peerName: String?) {
        val view = root ?: return
        val context = view.context
        val receiving = peerName != null
        val colour = context.getColor(
            if (receiving) R.color.saikai_red else R.color.saikai_green
        )

        label?.text = peerName ?: context.getString(R.string.app_name)
        (view.background as? GradientDrawable)?.setColor(colour)
        // Filled while receiving, hollow while idle: the second signal, so the
        // indicator does not rely on hue alone.
        (dot?.background as? GradientDrawable)?.apply {
            if (receiving) {
                setColor(Color.WHITE)
                setStroke(0, Color.TRANSPARENT)
            } else {
                setColor(Color.TRANSPARENT)
                setStroke(context.dp(2), Color.WHITE)
            }
        }
        view.contentDescription = label?.text
    }

    private fun buildView(context: Context): LinearLayout {
        val markSize = context.dp(12)
        val mark = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(context.dp(2), Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(markSize, markSize)
        }

        val text = TextView(context).apply {
            setTextColor(context.getColor(R.color.saikai_on_brand))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            setSingleLine(true)
            text = context.getString(R.string.app_name)
            setPadding(context.dp(6), 0, 0, 0)
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // So a screen reader announces it as something that can be
            // activated, and so performClick() below has an effect to report.
            isClickable = true
            setPadding(context.dp(12), context.dp(8), context.dp(14), context.dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = context.dp(20).toFloat()
                setColor(context.getColor(R.color.saikai_green))
            }
            addView(mark)
            addView(text)
            setOnTouchListener(DragAndTap(context) { openApp() })
        }.also {
            dot = mark
            label = text
        }
    }

    private fun buildLayout(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        // The only type an ordinary app may use since Android 8, and the one
        // SYSTEM_ALERT_WINDOW grants.
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Not focusable, so it never takes the keyboard or the back key from
        // whatever the user is actually using. It stays touchable, because being
        // tappable is the point.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = lastX
        y = lastY
    }

    /**
     * Brings the app forward.
     *
     * Starting an Activity from the background is restricted from Android 10 --
     * but holding SYSTEM_ALERT_WINDOW is one of the platform's own exemptions,
     * and this code only runs when a window is on screen, which requires it.
     *
     * The same intent the ongoing notification uses, for the same reason:
     * `getLaunchIntentForPackage` resolves the launcher Activity without naming
     * it here, and CLEAR_TOP brings the existing task forward instead of
     * stacking a second copy.
     */
    private fun openApp() {
        val intent = service.packageManager.getLaunchIntentForPackage(service.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?: return
        try {
            service.startActivity(intent)
        } catch (error: SecurityException) {
            logger.w(LogCategory.OVERLAY) { "could not open the app from the overlay" }
        }
    }

    /**
     * Move it with a drag, open the app with a tap.
     *
     * Both, because either alone is wrong: an indicator that cannot be moved
     * eventually sits on top of the one control the user needs, and one that
     * only moves has no way to do the thing section 19 says it must. The two are
     * told apart by distance, using the platform's own touch slop rather than a
     * number invented here.
     */
    private inner class DragAndTap(
        context: Context,
        private val onTap: () -> Unit,
    ) : View.OnTouchListener {

        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = layout ?: return false
            val manager = windowManager ?: return false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && kotlin.math.hypot(dx, dy) < slop) return true
                    dragging = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    try {
                        manager.updateViewLayout(view, params)
                    } catch (_: IllegalArgumentException) {
                        // The window went away mid-drag. Nothing to move.
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Remembered so a re-attach -- which happens every time
                        // the user leaves the app -- puts it back where they
                        // dropped it rather than in the corner.
                        lastX = params.x
                        lastY = params.y
                    } else {
                        view.performClick()
                        onTap()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> return true
            }
            return false
        }
    }

    private companion object {
        /**
         * Where the user last put it, for the lifetime of the process.
         *
         * Not persisted: a position is worth remembering across an app switch,
         * which happens constantly, and not worth a write to DataStore, which
         * would happen on every drag.
         */
        private var lastX = 0
        private var lastY = 160
    }
}

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
