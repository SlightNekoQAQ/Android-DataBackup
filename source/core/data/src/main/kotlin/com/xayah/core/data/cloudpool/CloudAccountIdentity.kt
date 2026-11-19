package com.xayah.core.data.cloudpool

import android.content.Context
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.C139Constants
import com.yunx.app.data.network.Pan115Api
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.UCApi
import java.io.IOException

/** Resolves a provider-verified account identifier; secrets and display names are not identities. */
internal suspend fun ImportedCloudAccount.verifiedIdentity(context: Context): String {
    val credentials = credentialFields()
    val id = when (platform) {
        "quark" -> QuarkApi().fetchNickname(credentials["cookie"].orEmpty())
        "uc" -> UCApi().fetchNickname(credentials["cookie"].orEmpty())
        "baidu" -> BaiduApi().fetchNickname(credentials["cookie"].orEmpty())
        "pan115" -> Pan115Api().fetchNickname(credentials["cookie"].orEmpty())
        "pan123" -> Pan123Api().fetchNickname(credentials["accessToken"].orEmpty())
        "c139" -> C139Constants.extractAccountFull(credentials["cookie"].orEmpty())
        else -> null
    }?.trim()?.takeIf(String::isNotEmpty)
        ?: throw IOException("Cannot verify the signed-in ${platform} account")

    return "$platform:$id"
}
