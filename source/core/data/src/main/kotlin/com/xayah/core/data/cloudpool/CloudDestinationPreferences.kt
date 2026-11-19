package com.xayah.core.data.cloudpool

import android.content.Context

/** Folder selections are scoped to the current credential import, not exported with credentials. */
class CloudDestinationPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("yunx-cloud-destinations-v1", Context.MODE_PRIVATE)

    fun read(slot: Int): String? = preferences.getString("folder-$slot", null)

    fun select(slot: Int, folderId: String) {
        require(slot >= 0 && folderId.isNotBlank())
        check(preferences.edit().putString("folder-$slot", folderId).commit()) { "Cannot save cloud destination" }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Cannot clear cloud destinations" }
    }
}
