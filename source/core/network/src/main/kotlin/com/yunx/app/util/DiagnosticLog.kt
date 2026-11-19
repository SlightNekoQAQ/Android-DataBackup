package com.yunx.app.util

/** The upstream diagnostic calls can carry response data. Do not persist those diagnostics. */
@Suppress("UNUSED_PARAMETER")
internal object DiagnosticLog {
    const val NETWORK = "network"
    fun event(module: String, event: String, summary: String? = null) = Unit
    fun warn(module: String, event: String, summary: String? = null) = Unit
    fun error(module: String, event: String, code: Any? = null, summary: String? = null) = Unit
}
