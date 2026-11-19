package com.yunx.app.data.network

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** Dedicated transport; provider-supplied credentials must never enter HTTP logs. */
internal object HttpClients {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun apiClient(): OkHttpClient = client
}
