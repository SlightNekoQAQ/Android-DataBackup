package com.xayah.core.data.cloudpool

/** One-shot handoff from the setup dialog to the in-process backup service. Never persisted. */
object CloudBackupRequest {
    enum class Kind { APPS, FILES }
    private data class Pending(val password: CharArray, val expiresAt: Long)
    private val pending = mutableMapOf<Kind, Pending>()

    @Synchronized
    fun arm(kind: Kind, password: CharArray) {
        require(password.size >= 8)
        pending.put(kind, Pending(password.copyOf(), android.os.SystemClock.elapsedRealtime() + 120_000))?.password?.fill('\u0000')
    }

    @Synchronized
    fun take(kind: Kind): CharArray? {
        val request = pending.remove(kind) ?: return null
        if (android.os.SystemClock.elapsedRealtime() > request.expiresAt) {
            request.password.fill('\u0000')
            return null
        }
        return request.password
    }
}
