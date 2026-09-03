/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.network.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers hosts whose retries have just been exhausted.
 *
 * A blocked or unreachable host fails identically for every source pointing at it, so the first
 * full retry cycle stands in for the rest of the batch: later calls get a single attempt until the
 * entry ages out, rather than each one paying the whole backoff over again.
 *
 * [now] is injectable so the cool-off window can be exercised without waiting for it.
 */
internal class HostFailureTracker(
    private val ttlMillis: Long,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val failures = ConcurrentHashMap<String, Long>()

    /** Whether [host] is still inside its cool-off window. A null host is never failing. */
    fun isFailing(host: String?): Boolean {
        val failedAt = failures[host ?: return false] ?: return false
        if (now() - failedAt <= ttlMillis) return true
        failures.remove(host)
        return false
    }

    fun markFailed(host: String) {
        failures[host] = now()
    }

    /** Drops the verdict once [host] answers again, so a recovered host is not held back. */
    fun clear(host: String) {
        failures.remove(host)
    }
}
