package app.morphe.manager

import java.util.concurrent.atomic.AtomicBoolean

/** Starts the post-license application runtime once, including after in-process activation. */
internal class LicensedRuntimeGate {
    private val started = AtomicBoolean(false)

    fun start(block: () -> Unit): Boolean {
        if (!started.compareAndSet(false, true)) return false
        try {
            block()
        } catch (error: Throwable) {
            started.set(false)
            throw error
        }
        return true
    }
}
