package com.xayah.feature.main.restore

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xayah.core.data.cloudpool.CloudBackupRepository
import com.xayah.core.data.cloudpool.CloudManifest
import com.xayah.core.model.Target
import com.xayah.core.ui.component.Checkable
import com.xayah.core.ui.component.Clickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun CloudRestoreContent(target: Target, viewModel: IndexViewModel, onContinue: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val repo = remember(context) { CloudBackupRepository(context) }
    val scope = rememberCoroutineScope()
    var backups by remember { mutableStateOf<List<CloudBackupRepository.RemoteBackup>>(emptyList()) }
    var backup by remember { mutableStateOf<CloudBackupRepository.RemoteBackup?>(null) }
    var entries by remember { mutableStateOf<List<CloudManifest.BackupEntry>>(emptyList()) }
    var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var missing by remember { mutableStateOf<List<String>>(emptyList()) }
    var unverifiable by remember { mutableStateOf<Set<String>>(emptySet()) }
    var ready by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableIntStateOf(R.string.restore_reading_backups) }
    val failed = stringResource(R.string.restore_cloud_failed)
    val emptyItems = stringResource(R.string.restore_no_items)
    val busy = loading != 0

    fun runStep(label: Int, operation: suspend () -> Unit) {
        loading = label
        error = null
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: failed
            } finally {
                loading = 0
            }
        }
    }
    fun reload() = runStep(R.string.restore_reading_backups) { backups = repo.discoverBackups() }
    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize()) {
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(loading), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f)) {
            val selected = backup
            if (selected == null) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.restore_choose_backup), Modifier.padding(top = 12.dp))
                        TextButton(enabled = !busy, onClick = { reload() }) { Text(stringResource(R.string.restore_refresh)) }
                    }
                }
                if (backups.isEmpty() && !busy && error == null) item {
                    Text(stringResource(R.string.restore_no_backups), Modifier.padding(16.dp))
                }
                items(backups, key = { it.backupId }) { candidate ->
                    Clickable(title = restoreBackupDate(candidate), value = candidate.provider, enabled = !busy) {
                        runStep(R.string.restore_reading_contents) {
                            val content = repo.backupContents(candidate)
                            entries = content.entries.filter { (it.kind == "app") == (target == Target.Apps) }
                            chosen = entries.map { it.path }.toSet()
                            backup = candidate
                        }
                    }
                }
            } else {
                item {
                    Clickable(title = restoreBackupDate(selected), value = stringResource(R.string.restore_change_backup), enabled = !busy) {
                        backup = null
                        entries = emptyList()
                        chosen = emptySet()
                        missing = emptyList()
                        unverifiable = emptySet()
                        ready = null
                        error = null
                    }
                }
                if (entries.isEmpty()) item { Text(emptyItems, Modifier.padding(16.dp)) }
                else item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.restore_selection_count, chosen.size, entries.size), Modifier.padding(top = 12.dp))
                        TextButton(enabled = !busy, onClick = {
                            chosen = if (chosen.size == entries.size) emptySet() else entries.map { it.path }.toSet()
                            ready = null
                            missing = emptyList()
                            unverifiable = emptySet()
                        }) {
                            Text(stringResource(if (chosen.size == entries.size) R.string.restore_clear_selection else R.string.restore_select_all))
                        }
                    }
                }
                items(entries, key = { it.path }) { entry ->
                    Checkable(enabled = !busy, checked = entry.path in chosen, title = entry.displayName, value = "") { checked ->
                        chosen = if (checked) chosen - entry.path else chosen + entry.path
                        ready = null
                        missing = emptyList()
                        unverifiable = emptySet()
                    }
                }
                if (unverifiable.isNotEmpty()) item {
                    Text(stringResource(R.string.restore_unverified_accounts, unverifiable.sorted().joinToString()), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                if (missing.isNotEmpty()) item {
                    Text(stringResource(R.string.restore_unavailable, missing.size), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                items(missing) { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            }
        }
        backup?.let { selected ->
            Button(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                enabled = !busy && chosen.isNotEmpty(),
                onClick = {
                    val folder = ready
                    if (folder != null) onContinue(folder)
                    else runStep(R.string.restore_downloading) {
                        missing = emptyList()
                        unverifiable = emptySet()
                        val result = repo.restoreAvailable(selected, chosen)
                        unverifiable = result.unverifiablePlatforms
                        missing = result.unavailableEntries.ifEmpty { result.missing.map { it.relativePath } }.distinct()
                        val selectedFolder = repo.selectedRestoreFolder(result.folder, entries, chosen, result.missing)
                        loading = R.string.restore_indexing
                        if (target in viewModel.indexCloudSnapshot(selectedFolder)) ready = selectedFolder.absolutePath
                        else error = emptyItems
                    }
                },
            ) {
                Text(stringResource(if (ready == null) R.string.restore_download_selected else R.string.restore_continue))
            }
        }
    }
}

@Composable
private fun restoreBackupDate(backup: CloudBackupRepository.RemoteBackup): String {
    val unknown = stringResource(R.string.restore_unknown_time)
    return remember(backup.createdAtMillis, unknown) {
        if (backup.createdAtMillis > 0) DateFormat.getDateTimeInstance().format(Date(backup.createdAtMillis)) else unknown
    }
}
