package com.xayah.feature.main.processing

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.xayah.core.data.cloudpool.CloudBackupRepository
import com.xayah.core.data.cloudpool.CloudBackupRequest
import com.xayah.core.ui.component.Clickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun CloudPoolBackupOption(kind: CloudBackupRequest.Kind, onStart: () -> Unit, onConfigure: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var available by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    LaunchedEffect(context) {
        available = try { withContext(Dispatchers.IO) { CloudBackupRepository(context).hasDestinations() } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { false }
    }
    Clickable(icon = Icons.Outlined.Cloud, title = stringResource(R.string.yunx_start_backup),
        value = if (available) stringResource(R.string.yunx_start_backup_desc) else stringResource(R.string.yunx_setup_destination)) {
        if (available) showPassword = true else onConfigure()
    }
    if (showPassword) AlertDialog(
        onDismissRequest = { password = ""; showPassword = false },
        title = { Text(stringResource(R.string.yunx_start_backup)) },
        text = { OutlinedTextField(password, { password = it }, singleLine = true,
            label = { Text(stringResource(R.string.yunx_pool_password)) }, visualTransformation = PasswordVisualTransformation()) },
        dismissButton = { TextButton(onClick = { password = ""; showPassword = false }) { Text(stringResource(android.R.string.cancel)) } },
        confirmButton = { TextButton(enabled = password.length >= 8, onClick = {
            val secret = password.toCharArray()
            try { CloudBackupRequest.arm(kind, secret) } finally { secret.fill('\u0000') }
            password = ""
            showPassword = false
            onStart()
        }) { Text(stringResource(android.R.string.ok)) } },
    )
}
