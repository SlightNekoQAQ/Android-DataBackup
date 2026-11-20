package com.xayah.feature.main.restore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xayah.core.data.cloudpool.CloudBackupRepository
import com.xayah.core.model.Target
import kotlinx.coroutines.CancellationException

/** Load source metadata only; the standard restore list owns all item selection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudRestoreContent(target: Target, viewModel: IndexViewModel, onContinue: (String) -> Unit) {
    val context = LocalContext.current.applicationContext
    val repo = remember(context) { CloudBackupRepository(context) }
    var missing by remember { mutableStateOf<List<String>>(emptyList()) }
    var unverifiable by remember { mutableStateOf<Set<String>>(emptySet()) }
    var ready by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var loading by remember { mutableIntStateOf(R.string.restore_reading_backups) }
    val failed = stringResource(R.string.restore_cloud_failed)
    val emptyItems = stringResource(R.string.restore_no_items)

    LaunchedEffect(target, retry) {
        loading = R.string.restore_reading_backups
        missing = emptyList()
        unverifiable = emptySet()
        ready = null
        error = null
        try {
            val result = repo.prepareRestore(if (target == Target.Apps) "app" else "file")
            missing = result.unavailable.distinct()
            unverifiable = result.unverifiablePlatforms
            loading = R.string.restore_indexing
            if (target !in viewModel.indexCloudSnapshot(result.folder)) error = emptyItems
            else if (missing.isEmpty() && unverifiable.isEmpty()) onContinue(result.folder.absolutePath)
            else ready = result.folder.absolutePath
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: failed
        } finally {
            loading = 0
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (loading != 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(loading), Modifier.padding(16.dp))
        }
        error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f)) {
            if (unverifiable.isNotEmpty()) item {
                Text(stringResource(R.string.restore_unverified_accounts, unverifiable.sorted().joinToString()), Modifier.padding(16.dp))
            }
            if (missing.isNotEmpty()) item {
                Text(stringResource(R.string.restore_unavailable, missing.size), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            }
            items(missing) { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        }
        if (loading == 0) {
            ready?.let { folder ->
                TextButton(onClick = { onContinue(folder) }) { Text(stringResource(R.string.restore_continue)) }
            }
            if (error != null || missing.isNotEmpty() || unverifiable.isNotEmpty()) {
                TextButton(onClick = { retry++ }) { Text(stringResource(R.string.restore_refresh)) }
            }
        }
    }
}
