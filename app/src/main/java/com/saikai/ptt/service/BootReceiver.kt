package com.saikai.ptt.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.saikai.ptt.SaikaiApplication
import com.saikai.ptt.core.logger.LogCategory

/**
 * Brings the service back after a reboot, able to receive.
 *
 * `docs/ADR/ADR-005` section 4. The service starts with the `connectedDevice`
 * type alone, which Android 14 permits from BOOT_COMPLETED; `microphone` is
 * not permitted from a broadcast, and that is the whole shape of the state the
 * device wakes up in:
 *
 * > **It can hear, and it cannot talk.** Transmitting needs the microphone
 * > type, and that promotion needs a visible screen (ADR-005 sections 2 and 3),
 * > so opening the app is what restores the other half. That is a platform
 * > rule, not a limitation of this receiver, and the UI has to say so
 * > (`docs/04_UI_UX.md` section 36).
 *
 * The service is started without asking whether this device has ever been
 * configured. Checking would mean reading DataStore, and doing that before
 * `startForegroundService` means letting the BOOT_COMPLETED broadcast finish
 * first -- which is the exemption that allows a foreground service to be
 * started from the background at all. Trading a certainty for a nicety is the
 * wrong way round here; a device with no name announces nothing and simply
 * sits there, which is a wasted notification rather than a fault.
 *
 * Some manufacturers' ROMs refuse autostart outright, and no code here can
 * change that. ADR-005 section 4 makes it the permission screen's job to say so
 * -- without hard-coding a path into any vendor's settings app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // A receiver is handed whatever the system sends it, including, on some
        // devices, actions it never registered for.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val logger = (context.applicationContext as? SaikaiApplication)?.container?.logger
        try {
            CommunicationService.start(context)
            logger?.i(LogCategory.SERVICE) { "started after boot; receiving only until the app is opened" }
        } catch (error: RuntimeException) {
            // A ROM that forbids autostart, or a platform that refused the
            // foreground start. Neither is recoverable from here, and neither
            // is worth crashing a boot broadcast over.
            logger?.w(LogCategory.SERVICE, error) { "could not start after boot" }
        }
    }
}
