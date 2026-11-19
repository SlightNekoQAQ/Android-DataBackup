package com.xayah.feature.main.cloud

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xayah.core.data.cloudpool.ImportedCloudAccount
import com.yunx.app.data.network.model.ShareFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun providerName(platform: String): String = when (platform) {
    "quark" -> "夸克网盘"
    "uc" -> "UC 网盘"
    "xunlei" -> "迅雷网盘"
    "baidu" -> "百度网盘"
    "c139" -> "139 网盘"
    "pan123" -> "123 云盘"
    "pan115" -> "115 网盘"
    "guangya" -> "光鸭云盘"
    "ilanzou" -> "蓝奏云优享版"
    else -> platform
}

@Composable
internal fun YunxAccountBrowser(account: ImportedCloudAccount, onDismiss: () -> Unit, onSelect: suspend (String) -> Unit) {
    val context = LocalContext.current
    val drive = remember(account, context) { account.personalDrive(context.applicationContext) }
    var parents by remember(account) { mutableStateOf(listOf(drive.rootId)) }
    var files by remember { mutableStateOf<List<ShareFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(drive, parents) {
        loading = true
        failed = false
        files = emptyList()
        try {
            files = withContext(Dispatchers.IO) { drive.list(parents.last()) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(providerName(account.platform)) },
        text = {
            when {
                loading -> CircularProgressIndicator()
                failed -> Text(stringResource(R.string.yunx_browse_failed))
                files.isEmpty() -> Text(stringResource(R.string.yunx_empty_folder))
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(files, key = { it.fid }) { file ->
                        if (file.isdir) {
                            TextButton(onClick = { parents = parents + file.fid }) { Text(file.fname) }
                        } else {
                            Text(file.fname)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !loading && !failed && !saving && parents.size > 1 && parents.last().isNotBlank(), onClick = {
                saving = true
                scope.launch {
                    try { onSelect(parents.last()) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { failed = true }
                    finally { saving = false }
                }
            }) { Text(stringResource(R.string.yunx_choose_folder)) }
        },
        dismissButton = {
            if (parents.size > 1) TextButton(onClick = { parents = parents.dropLast(1) }) {
                Text(stringResource(R.string.yunx_parent_folder))
            }
        },
    )
}
