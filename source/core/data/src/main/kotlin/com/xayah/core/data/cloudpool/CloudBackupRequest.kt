package com.xayah.core.data.cloudpool

/**
 * One-shot signal from the setup screen to the in-process backup service, so the service knows the
 * user chose the cloud pool for this run. No secret material is carried: the cloud accounts that
 * are already configured are the only thing required, so nothing needs to be typed or remembered.
 */
object CloudBackupRequest {
    enum class Kind { APPS, FILES }
    private val pending = mutableMapOf<Kind, Long>()
    private const val TTL_MS = 120_000L

    @Synchronized
    fun arm(kind: Kind) {
        pending[kind] = android.os.SystemClock.elapsedRealtime() + TTL_MS
    }

    @Synchronized
    fun disarm(kind: Kind) {
        pending.remove(kind)
    }

    @Synchronized
    fun take(kind: Kind): Boolean {
        val expiresAt = pending.remove(kind) ?: return false
        return android.os.SystemClock.elapsedRealtime() <= expiresAt
    }
}
