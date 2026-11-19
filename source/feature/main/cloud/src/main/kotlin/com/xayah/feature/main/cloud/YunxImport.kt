package com.xayah.feature.main.cloud

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.xayah.core.data.cloudpool.CloudAccountVault
import com.xayah.core.data.cloudpool.CloudBackupRepository
import com.xayah.core.data.cloudpool.CloudDestinationPreferences
import com.xayah.core.data.cloudpool.ImportedCloudAccount
import com.xayah.core.data.cloudpool.YunxCredentialImport
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.Title
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun YunxImport() {
    val context = LocalContext.current.applicationContext
    val vault = remember(context) { CloudAccountVault(context) }
    val destinations = remember(context) { CloudDestinationPreferences(context) }
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf<List<ImportedCloudAccount>>(emptyList()) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        if (selected != null) {
            password = ""
            uri = selected
            failed = false
        }
    }
    LaunchedEffect(vault) {
        try {
            accounts = withContext(Dispatchers.IO) { vault.load() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }
    var inspected by remember { mutableStateOf<Int?>(null) }
    val repository = remember(context) { CloudBackupRepository(context) }
    var configured by remember { mutableStateOf(emptySet<Int>()) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(accounts, refresh) {
        configured = if (accounts.isEmpty()) emptySet() else repository.prepareDestinations()
            .filter { it.ready }.map { it.slot }.toSet()
    }
    Title(title = stringResource(R.string.yunx_account_group)) {
        accounts.forEachIndexed { slot, account ->
            Clickable(
                enabled = !busy,
                icon = Icons.Outlined.Cloud,
                title = providerName(account.platform),
                value = listOf(account.nickname, stringResource(if (slot in configured) R.string.yunx_backup_ready else R.string.yunx_backup_retry)).filter(String::isNotBlank).joinToString(" · "),
            ) { if (slot in configured) inspected = slot else refresh++ }
        }
        Clickable(
            enabled = !busy,
            icon = Icons.Rounded.Add,
            title = stringResource(R.string.yunx_import_accounts),
        ) { picker.launch(arrayOf("*/*")) }
        if (failed && uri == null) Text(stringResource(R.string.yunx_import_failed))
    }
    inspected?.let { slot ->
        accounts.getOrNull(slot)?.let { account ->
            YunxAccountBrowser(account, slot, onDismiss = { inspected = null })
        }
    }
    val selected = uri ?: return
    AlertDialog(
        onDismissRequest = { if (!busy) { password = ""; uri = null } },
        title = { Text(stringResource(R.string.yunx_import_accounts)) },
        text = {
            Column {
                Text(stringResource(R.string.yunx_import_replace))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = !busy,
                    label = { Text(stringResource(R.string.yunx_export_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (failed) Text(stringResource(R.string.yunx_import_failed))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                failed = false
                val pass = password.toCharArray()
                password = ""
                scope.launch {
                    try {
                        accounts = withContext(Dispatchers.IO) {
                            val content = context.contentResolver.openInputStream(selected)?.bufferedReader()?.use { reader ->
                                val result = StringBuilder()
                                val buffer = CharArray(8192)
                                while (true) {
                                    val read = reader.read(buffer)
                                    if (read < 0) break
                                    require(result.length + read <= 4 * 1024 * 1024)
                                    result.append(buffer, 0, read)
                                }
                                result.toString()
                            } ?: error("Unable to read credential document")
                            YunxCredentialImport.parse(content, pass).also { imported ->
                                require(imported.isNotEmpty())
                                destinations.clear()
                                vault.save(imported)
                            }
                        }
                        uri = null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failed = true
                    } finally {
                        pass.fill('\u0000')
                        busy = false
                    }
                }
            }) { Text(stringResource(R.string.yunx_import_confirm)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = { password = ""; uri = null }) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
