package com.xayah.core.data.cloudpool

/** The ID identifies a specific account, not merely a provider. Sizes are in bytes. */
data class CloudTarget(
    val accountId: String,
    val priority: Int,
    val downloadBytesPerSecond: Long?,
    val freeBytes: Long?,
    val maxFileBytes: Long?,
    val writable: Boolean = true,
) {
    init {
        require(accountId.isNotBlank())
        require(priority >= 0)
        require(downloadBytesPerSecond == null || downloadBytesPerSecond >= 0)
        require(freeBytes == null || freeBytes >= 0)
        require(maxFileBytes == null || maxFileBytes >= 0)
    }
}

/** Calculates candidates only. Persist a reservation before uploading; never split a file here. */
object CloudPlacement {
    fun candidates(
        fileBytes: Long,
        targets: List<CloudTarget>,
        reservedBytes: Map<String, Long> = emptyMap(),
        excludedAccounts: Set<String> = emptySet(),
        useManualOrder: Boolean = false,
    ): List<CloudTarget> {
        require(fileBytes >= 0)
        require(reservedBytes.values.all { it >= 0 })
        require(targets.map { it.accountId }.distinct().size == targets.size)

        val byPriority = compareBy<CloudTarget> { it.priority }.thenBy { it.accountId }
        val order = if (useManualOrder) byPriority else {
            compareByDescending<CloudTarget> { it.downloadBytesPerSecond ?: -1L }.then(byPriority)
        }
        return targets.asSequence()
            .filter { target ->
                val reserved = reservedBytes[target.accountId] ?: 0L
                val free = target.freeBytes
                target.writable && target.accountId !in excludedAccounts &&
                    (target.maxFileBytes == null || fileBytes <= target.maxFileBytes) &&
                    (free == null || (reserved <= free && fileBytes <= free - reserved))
            }
            .sortedWith(order)
            .toList()
    }
}
