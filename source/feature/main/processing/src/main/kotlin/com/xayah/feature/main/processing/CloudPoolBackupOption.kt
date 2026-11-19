package com.xayah.feature.main.processing

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.xayah.core.data.cloudpool.CloudBackupRepository
import com.xayah.core.data.cloudpool.CloudBackupRequest
import com.xayah.core.ui.component.Clickable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Selecting this is enough to choose the cloud pool: the configured cloud accounts are the
 * credential, so no passphrase is requested, typed or stored anywhere.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun CloudPoolBackupOption(kind: CloudBackupRequest.Kind, onStart: () -> Unit, onConfigure: () -> Unit) {
    val context = LocalContext.current.applicationContext
    var available by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        available = try {
            withContext(Dispatchers.IO) { CloudBackupRepository(context).hasDestinations() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }
    Clickable(
        icon = Icons.Outlined.Cloud,
        title = stringResource(R.string.yunx_start_backup),
        value = if (available) stringResource(R.string.yunx_start_backup_desc) else stringResource(R.string.yunx_setup_destination),
    ) {
        if (available) {
            CloudBackupRequest.arm(kind)
            onStart()
        } else {
            onConfigure()
        }
    }
}
