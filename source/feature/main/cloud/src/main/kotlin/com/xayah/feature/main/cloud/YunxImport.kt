package com.xayah.feature.main.cloud

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
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
import com.xayah.core.data.cloudpool.ImportedCloudAccount
import com.xayah.core.data.cloudpool.YunxCredentialImport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun YunxImport() {
    val context = LocalContext.current.applicationContext
    val vault = remember(context) { CloudAccountVault(context) }
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
    Column {
        TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(stringResource(R.string.yunx_import_accounts))
        }
        accounts.forEach { account -> Text(listOf(account.platform, account.nickname).filter(String::isNotBlank).joinToString(" · ")) }
        if (failed) Text(stringResource(R.string.yunx_import_failed))
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
