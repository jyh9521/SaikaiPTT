package com.saikai.ptt.di

import com.saikai.ptt.core.config.SaikaiConfig

/**
 * Application-scope dependencies: the objects that live as long as the process.
 *
 * Hand-written rather than a DI framework (`docs/02_Architecture.md` section 7).
 * The object graph is a few dozen entries, the wiring is readable as plain code
 * with nothing generated, and tests construct what they need directly.
 *
 * Two scopes exist. This is the outer one. Communication components --
 * transport, discovery, presence, session manager, audio, overlay -- belong to a
 * **service scope** created and destroyed with the Foreground Service (Task15),
 * so that stopping the service actually releases the sockets and audio devices
 * instead of leaving them owned by a process-lifetime object.
 *
 * @param isDebugBuild supplied by the caller rather than read from `BuildConfig`
 *   here, so the container stays constructible in a plain JVM test.
 */
class AppContainer(isDebugBuild: Boolean) {

    /**
     * Every tunable value in the app. Injected rather than read from a global so
     * tests can shorten timeouts instead of waiting them out.
     */
    val config: SaikaiConfig = SaikaiConfig.forBuild(isDebugBuild)
}
