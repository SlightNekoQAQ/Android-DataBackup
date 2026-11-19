package com.xayah.feature.main.cloud

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
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
import com.xayah.core.data.cloudpool.CloudBackupRepository
import com.xayah.core.data.cloudpool.CloudDestinationPreferences
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
internal fun YunxAccountBrowser(account: ImportedCloudAccount, slot: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val drive = remember(account, context) { account.personalDrive(context.applicationContext) }
    val folder = remember(slot, account) { preferences.defaultFolder(slot) } ?: return
    var parents by remember(account, folder) { mutableStateOf(listOf(folder)) }
    var files by remember { mutableStateOf<List<ShareFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val repository = remember(context) { CloudBackupRepository(context) }
    val preferences = remember(context) { CloudDestinationPreferences(context.applicationContext) }
    var connections by remember(slot) { mutableStateOf(preferences.connections(slot)) }
    var speed by remember { mutableStateOf<Long?>(null) }
    var speedFailed by remember { mutableStateOf(false) }
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
        title = { Text("${providerName(account.platform)} · /backup") },
        text = {
            Column {
                if (parents.size == 1) {
                    Text(stringResource(R.string.yunx_connections, connections))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 4, 8, 16, 32).forEach { count ->
                            TextButton(enabled = !saving && count != connections, onClick = {
                                saving = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { preferences.setConnections(slot, count) }
                                        connections = count
                                        speed = null
                                        speedFailed = false
                                    } catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { speedFailed = true }
                                    finally { saving = false }
                                }
                            }) { Text(count.toString()) }
                        }
                    }
                }
                when {
                loading -> CircularProgressIndicator()
                failed -> Text(stringResource(R.string.yunx_browse_failed))
                files.isEmpty() -> Text(stringResource(R.string.yunx_empty_folder))
                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(files, key = { it.fid }) { file ->
                        if (file.isdir) {
                            TextButton(onClick = { parents = parents + drive.directoryId(file) }) { Text(file.fname) }
                        } else {
                            Text(file.fname)
                        }
                    }
                }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = {
            if (parents.size > 1) TextButton(enabled = !saving, onClick = { parents = parents.dropLast(1) }) {
                Text(stringResource(R.string.yunx_parent_folder))
            } else TextButton(enabled = !saving, onClick = {
                saving = true
                speedFailed = false
                scope.launch {
                    try { speed = repository.measureSpeed(slot) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { speedFailed = true }
                    finally { saving = false }
                }
            }) {
                Text(when {
                    speedFailed -> stringResource(R.string.yunx_speed_failed)
                    speed != null -> stringResource(R.string.yunx_speed_result, (speed ?: 0L) / 1024L)
                    else -> stringResource(R.string.yunx_speed_test)
                })
            }
        },
    )
}
