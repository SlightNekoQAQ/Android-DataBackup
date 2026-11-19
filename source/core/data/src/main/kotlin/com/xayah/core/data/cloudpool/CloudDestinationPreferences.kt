package com.xayah.core.data.cloudpool

import android.content.Context

/** Folder selections are scoped to the current credential import, not exported with credentials. */
class CloudDestinationPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("yunx-cloud-destinations-v1", Context.MODE_PRIVATE)

    fun read(slot: Int): String? = preferences.getString("folder-$slot", null)

    fun defaultFolder(slot: Int): String? = preferences.getString("default-folder-$slot", null)

    fun saveDefaultFolder(slot: Int, folderId: String) {
        require(slot >= 0 && folderId.isNotBlank())
        check(preferences.edit().putString("default-folder-$slot", folderId).commit())
    }

    fun connections(slot: Int): Int = preferences.getInt("connections-$slot", 8).coerceIn(1, 32)

    fun setConnections(slot: Int, count: Int) {
        require(slot >= 0 && count in 1..32)
        check(preferences.edit().putInt("connections-$slot", count).remove("speed-$slot").commit()) {
            "Cannot save download settings"
        }
    }

    fun speed(slot: Int): Long? = preferences.getLong("speed-$slot", -1L).takeIf { it >= 0 }

    fun recordSpeed(slot: Int, bytesPerSecond: Long) {
        require(slot >= 0 && bytesPerSecond > 0)
        check(preferences.edit().putLong("speed-$slot", bytesPerSecond).commit())
    }

    fun select(slot: Int, folderId: String) {
        require(slot >= 0 && folderId.isNotBlank())
        check(preferences.edit().putString("folder-$slot", folderId).commit()) { "Cannot save cloud destination" }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Cannot clear cloud destinations" }
    }
}
