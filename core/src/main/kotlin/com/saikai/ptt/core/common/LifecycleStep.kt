package com.saikai.ptt.core.common

/**
 * One resource that is acquired in order and released in exactly the reverse.
 *
 * Lives in `core.common` rather than beside the service that runs the sequence,
 * because the things that implement it -- the transport, discovery, presence,
 * later the audio devices -- sit in layers the service is allowed to know about
 * and that are not allowed to know about the service. An interface small enough
 * to have no dependencies of its own is the cheapest way to keep that arrow
 * pointing one way.
 */
interface LifecycleStep {

    /** Short, stable, and safe to log. Identifies the step in a failure. */
    val name: String

    /** Acquires the resource. Throwing means the start failed at this step. */
    suspend fun start()

    /**
     * Releases the resource.
     *
     * Always called during rollback, including for the step whose [start] threw,
     * so it must tolerate a start that never ran or only half ran. It should not
     * throw -- a throw here is logged and the remaining steps are still
     * released, but a step that cleans up quietly is easier to trust.
     */
    suspend fun stop()
}
