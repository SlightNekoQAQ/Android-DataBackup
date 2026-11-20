package com.xayah.feature.main.processing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.xayah.core.data.cloudpool.CloudBackupRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberCloudPoolAvailable(selected: Boolean): Boolean {
    val context = LocalContext.current.applicationContext
    var available by remember(context, selected) { mutableStateOf(false) }
    LaunchedEffect(context, selected) {
        if (selected) {
            available = try {
                withContext(Dispatchers.IO) { CloudBackupRepository(context).hasDestinations() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
    }
    return available
}
