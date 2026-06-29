package app.mls.server.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-key login throttle with lockout. Keyed by `email|ip` so neither a single
 * account nor a single source can be hammered. Suitable for a single-node self-host; a
 * multi-node deployment would back this with Redis (documented in server/README.md).
 */
class RateLimiter(
    private val maxAttempts: Int,
    private val lockoutMillis: Long,
    private val now: () -> Long,
) {
    private class Entry(var count: Int, var windowStart: Long, var lockedUntil: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** True if [key] may attempt right now (not currently locked out). */
    fun checkAllowed(key: String): Boolean {
        val e = entries[key] ?: return true
        return now() >= e.lockedUntil
    }

    fun recordFailure(key: String) {
        val t = now()
        entries.compute(key) { _, prev ->
            val e = prev ?: Entry(0, t, 0)
            if (t - e.windowStart > lockoutMillis) { // window expired -> reset
                e.count = 0
                e.windowStart = t
            }
            e.count += 1
            if (e.count >= maxAttempts) {
                e.lockedUntil = t + lockoutMillis
                e.count = 0
                e.windowStart = t
            }
            e
        }
    }

    fun recordSuccess(key: String) {
        entries.remove(key)
    }
}
