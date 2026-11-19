package com.xayah.feature.main.cloud

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xayah.core.data.cloudpool.CloudDestinationPreferences
import com.xayah.core.data.cloudpool.ImportedCloudAccount
import com.xayah.core.data.cloudpool.YunxCredentialImport
import com.xayah.core.data.cloudpool.uploadSlotOrder
import com.xayah.core.ui.component.Clickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun UploadOrderOption(accounts: List<ImportedCloudAccount>, preferences: CloudDestinationPreferences) {
    val slots = accounts.indices.filter { YunxCredentialImport.supports(accounts[it].platform) }
    if (slots.isEmpty()) return
    val scope = rememberCoroutineScope()
    var stored by remember(accounts) { mutableStateOf(preferences.manualOrder()) }
    var editing by remember(accounts) { mutableStateOf<List<Int>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    fun save(order: List<Int>?) {
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { preferences.saveManualOrder(order) }
                stored = order
                editing = null
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { failed = true }
            finally { saving = false }
        }
    }
    Clickable(title = stringResource(R.string.yunx_upload_order),
        value = stringResource(if (stored == null) R.string.yunx_order_auto else R.string.yunx_order_manual)) {
        failed = false
        val bySpeed = slots.sortedByDescending { preferences.speed(it) ?: -1L }
        editing = uploadSlotOrder(bySpeed, stored)
    }
    val draft = editing ?: return
    AlertDialog(onDismissRequest = { if (!saving) editing = null },
        title = { Text(stringResource(R.string.yunx_upload_order)) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.yunx_order_desc))
                draft.forEachIndexed { index, slot ->
                    val account = accounts[slot]
                    Text("${index + 1}. ${providerName(account.platform)} ${account.nickname}".trimEnd())
                    Row {
                        TextButton(enabled = !saving && index > 0, onClick = {
                            editing = draft.toMutableList().apply { add(index - 1, removeAt(index)) }
                        }) { Text(stringResource(R.string.yunx_order_up)) }
                        TextButton(enabled = !saving && index < draft.lastIndex, onClick = {
                            editing = draft.toMutableList().apply { add(index + 1, removeAt(index)) }
                        }) { Text(stringResource(R.string.yunx_order_down)) }
                    }
                }
                if (failed) Text(stringResource(R.string.yunx_job_failed))
            }
        },
        confirmButton = { TextButton(enabled = !saving, onClick = { save(draft) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(enabled = !saving, onClick = { save(null) }) { Text(stringResource(R.string.yunx_order_auto)) } },
    )
}
