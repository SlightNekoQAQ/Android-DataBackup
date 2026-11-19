package com.xayah.feature.main.cloud

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xayah.core.data.cloudpool.CloudBackupRepository
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.Title
import com.xayah.core.util.localBackupSaveDir
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.withContext

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun CloudSnapshotActions(uploadEnabled: Boolean = true, onRestore: (suspend (File) -> Unit)? = null) {
    val restoreEnabled = onRestore != null
    val context = LocalContext.current.applicationContext
    val repository = remember(context) { CloudBackupRepository(context) }
    val scope = rememberCoroutineScope()
    val progressUpdates = remember { MutableStateFlow<Pair<Int, Int>?>(null) }
    val progress by progressUpdates.collectAsState()
    var mode by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<CloudBackupRepository.RemoteBackup>?>(null) }
    var selected by remember { mutableStateOf<CloudBackupRepository.RemoteBackup?>(null) }
    var locations by remember { mutableStateOf<List<CloudBackupRepository.FileLocation>?>(null) }
    var verifiedFolder by remember { mutableStateOf<File?>(null) }
    var receipt by remember(repository) { mutableStateOf(repository.lastConfirmedUpload()) }
    val failed = stringResource(R.string.yunx_job_failed)
    val empty = stringResource(R.string.yunx_no_backups)
    val complete = stringResource(R.string.yunx_upload_complete)
    val incomplete = stringResource(R.string.yunx_manifest_partial)
    val restored = stringResource(R.string.yunx_restore_path)
    Title(title = stringResource(R.string.yunx_snapshot_section)) {
        receipt?.let {
            Clickable(readOnly = true, title = stringResource(R.string.yunx_upload_receipt), value = it)
        }
        if (uploadEnabled) Clickable(enabled = !busy, icon = Icons.Outlined.Cloud, title = stringResource(R.string.yunx_upload_local),
            desc = stringResource(R.string.yunx_upload_local_desc)) {
            message = ""
            verifiedFolder = null
            mode = 0
        }
        Clickable(enabled = !busy, icon = Icons.Outlined.Cloud, title = stringResource(if (restoreEnabled) R.string.yunx_download_snapshot else R.string.yunx_locations)) {
            busy = true
            message = ""
            scope.launch {
                try {
                    val found = repository.available()
                    if (found.isEmpty()) message = empty else candidates = found
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    message = failed
                } finally { busy = false }
            }
        }
        verifiedFolder?.let { folder ->
            Clickable(enabled = !busy, title = stringResource(R.string.yunx_open_restore),
                desc = stringResource(R.string.yunx_open_restore_desc)) {
                busy = true
                scope.launch {
                    try {
                        checkNotNull(onRestore)(folder)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        message = failed
                    } finally { busy = false }
                }
            }
        }
        if (busy || message.isNotBlank()) Clickable(
            readOnly = true,
            title = if (busy) progress?.let { (done, total) -> stringResource(R.string.yunx_job_progress, done, total) }
                ?: stringResource(R.string.yunx_job_running) else message,
        )
    }
    candidates?.let { options ->
        AlertDialog(onDismissRequest = { candidates = null },
            title = { Text(stringResource(R.string.yunx_download_snapshot)) },
            text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(options) { backup ->
                    TextButton(onClick = { selected = backup; verifiedFolder = null; candidates = null; mode = if (restoreEnabled) 1 else 2 }) {
                        Text("${providerName(backup.provider)} · ${backup.label}")
                    }
                    if (restoreEnabled) TextButton(onClick = { selected = backup; candidates = null; mode = 2 }) {
                        Text(stringResource(R.string.yunx_locations))
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { candidates = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    locations?.let { entries ->
        AlertDialog(onDismissRequest = { locations = null }, title = { Text(stringResource(R.string.yunx_locations)) },
            text = { LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(entries, key = { it.original }) { entry ->
                    Column {
                        Text(entry.original)
                        Text(listOf(providerName(entry.provider), entry.account).filter(String::isNotBlank).joinToString(" · "))
                        Text(entry.remoteName?.let { "${entry.folder.ifBlank { "…" }}/$it" }
                            ?: stringResource(R.string.yunx_location_unverified))
                    }
                }
            } }, confirmButton = { TextButton(onClick = { locations = null }) { Text(stringResource(android.R.string.ok)) } })
    }
    val action = mode ?: return
    AlertDialog(
        onDismissRequest = { if (!busy) mode = null },
        title = {
            Text(stringResource(when (action) {
                0 -> R.string.yunx_upload_local
                1 -> R.string.yunx_download_snapshot
                else -> R.string.yunx_locations
            }))
        },
        text = { Text(stringResource(R.string.yunx_no_passphrase_hint)) },
        dismissButton = { TextButton(enabled = !busy, onClick = { mode = null }) { Text(stringResource(android.R.string.cancel)) } },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val backup = selected
                mode = null
                busy = true
                progressUpdates.value = null
                scope.launch {
                    try {
                        val localRoot = withContext(Dispatchers.IO) { File(context.localBackupSaveDir()) }
                        message = if (action == 0) {
                            val result = repository.publish(localRoot) { done, total -> progressUpdates.value = done to total }
                            receipt = repository.lastConfirmedUpload()
                            if (result.missingCopies.isEmpty()) complete else incomplete
                        } else if (action == 1) {
                            verifiedFolder = repository.restore(checkNotNull(backup)) { done, total -> progressUpdates.value = done to total }
                            restored
                        } else {
                            locations = repository.locations(checkNotNull(backup))
                            ""
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        message = failed
                    } finally {
                        progressUpdates.value = null
                        busy = false
                    }
                }
            }) { Text(stringResource(android.R.string.ok)) }
        },
    )
}
