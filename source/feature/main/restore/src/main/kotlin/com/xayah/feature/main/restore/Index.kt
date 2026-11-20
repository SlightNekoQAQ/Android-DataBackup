package com.xayah.feature.main.restore

import android.annotation.SuppressLint
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xayah.core.model.OpType
import com.xayah.core.model.StorageMode
import com.xayah.core.model.Target
import com.xayah.core.ui.component.Clickable
import com.xayah.core.ui.component.LocalSlotScope
import com.xayah.core.ui.component.PackageIcons
import com.xayah.core.ui.component.Selectable
import com.xayah.core.ui.component.Title
import com.xayah.core.ui.component.paddingBottom
import com.xayah.core.ui.component.paddingHorizontal
import com.xayah.core.ui.component.paddingTop
import com.xayah.core.ui.component.select
import com.xayah.core.ui.route.MainRoutes
import com.xayah.core.ui.token.SizeTokens
import com.xayah.core.ui.util.LocalNavController
import com.xayah.core.ui.util.icon
import com.xayah.core.util.encodeURL
import com.xayah.core.util.navigateSingle

@SuppressLint("StringFormatInvalid")
@ExperimentalFoundationApi
@ExperimentalLayoutApi
@ExperimentalAnimationApi
@ExperimentalMaterial3Api
@Composable
fun PageRestore() {
    val navController = LocalNavController.current!!
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val viewModel = hiltViewModel<IndexViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lastRestoreTime by viewModel.lastRestoreTimeState.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()

    LaunchedEffect(null) {
        viewModel.launchOnIO {
            viewModel.emitIntent(IndexUiIntent.UpdateApps)
            viewModel.emitIntent(IndexUiIntent.UpdateFiles)
        }
    }

    RestoreScaffold(
        scrollBehavior = scrollBehavior,
        title = stringResource(id = R.string.restore),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            OverviewLastRestoreCard(
                modifier = Modifier.padding(SizeTokens.Level16),
                lastRestoreTime = lastRestoreTime
            )

            var enabled by remember { mutableStateOf(true) }
            val storageOptions = listOf(stringResource(R.string.local), stringResource(R.string.restore_protocol), stringResource(R.string.restore_cloud_pool))
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .paddingHorizontal(SizeTokens.Level16)
                    .paddingBottom(SizeTokens.Level16),
            ) {
                storageOptions.forEachIndexed { index, label ->
                    SegmentedButton(
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = storageOptions.size),
                        onClick = {
                            viewModel.launchOnIO {
                                enabled = false
                                viewModel.emitState(state = uiState.copy(storageIndex = index, storageType = if (index == 1) StorageMode.Cloud else StorageMode.Local))
                                viewModel.emitIntent(IndexUiIntent.UpdateApps)
                                viewModel.emitIntent(IndexUiIntent.UpdateFiles)
                                enabled = true
                            }
                        },
                        selected = index == uiState.storageIndex
                    ) {
                        Text(label)
                    }
                }
            }

            if (uiState.storageIndex == 2) {
                var backups by remember { mutableStateOf<List<com.xayah.core.data.cloudpool.CloudBackupRepository.RemoteBackup>>(emptyList()) }
                var selected by remember { mutableStateOf<com.xayah.core.data.cloudpool.CloudBackupRepository.RemoteBackup?>(null) }
                var entries by remember { mutableStateOf<List<com.xayah.core.data.cloudpool.CloudManifest.BackupEntry>>(emptyList()) }
                var unavailableAccounts by remember { mutableStateOf<Set<String>>(emptySet()) }
                var unverifiablePlatforms by remember { mutableStateOf<Set<String>>(emptySet()) }
                var chosen by remember { mutableStateOf<Set<String>>(emptySet()) }
                var missing by remember { mutableStateOf<List<String>>(emptyList()) }
                var busy by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                var busyLabel by remember { mutableStateOf("正在读取备份列表…") }
                var showAllBackups by remember { mutableStateOf(false) }
                var showAllEntries by remember { mutableStateOf(false) }
                val context = LocalContext.current.applicationContext
                val cloudRepo = remember(context) { com.xayah.core.data.cloudpool.CloudBackupRepository(context) }
                val scope = rememberCoroutineScope()
                LaunchedEffect(Unit) {
                    busy = true
                    busyLabel = "正在读取备份列表…"
                    scope.launch {
                        try {
                            backups = cloudRepo.discoverBackups()
                            error = null
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "读取网盘备份失败"
                        } finally {
                            busy = false
                        }
                    }
                }
                if (busy) Clickable(readOnly = true, title = busyLabel)
                error?.let { Clickable(readOnly = true, title = it) }
                val shownBackups = if (showAllBackups) backups else backups.take(30)
                shownBackups.forEach { backup ->
                    val dateLabel = if (backup.createdAtMillis > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(backup.createdAtMillis)) else "时间未知"
                    Clickable(title = dateLabel, value = backup.provider, enabled = !busy) {
                        scope.launch {
                            busy = true
                            busyLabel = "正在读取备份目录…"
                            selected = backup
                            entries = emptyList()
                            try {
                                val content = cloudRepo.backupContents(backup)
                        entries = content.entries
                        unavailableAccounts = content.missingAccounts
                        unverifiablePlatforms = emptySet()
                        chosen = content.entries.map { it.path }.toSet()
                        showAllEntries = false
                                missing = emptyList()
                                error = null
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "读取备份索引失败"
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
                if (backups.size > shownBackups.size) {
                    Clickable(title = "显示剩余 ${backups.size - shownBackups.size} 份备份") { showAllBackups = true }
                }
                selected?.let { backup ->
                    Text("已选择：${if (backup.createdAtMillis > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(backup.createdAtMillis)) else "时间未知"}")
                    val shownEntries = if (showAllEntries) entries else entries.take(50)
                    shownEntries.forEach { entry ->
                        val checked = entry.path in chosen
                        Clickable(title = "${if (entry.kind == "app") "应用" else "文件"} · ${entry.displayName}", value = if (checked) "已选择" else "未选择") {
                            chosen = if (checked) chosen - entry.path else chosen + entry.path
                        }
                    }
                    if (unavailableAccounts.isNotEmpty()) Clickable(readOnly = true, title = "缺少网盘账号：${unavailableAccounts.joinToString()}")
                    if (unverifiablePlatforms.isNotEmpty()) Clickable(readOnly = true, title = "账号身份验证失败的网盘：${unverifiablePlatforms.joinToString()}，请重新从 YunX 导入正确账号")
                    if (missing.isNotEmpty()) Clickable(readOnly = true, title = "未恢复：${missing.joinToString()}")
                    if (entries.size > shownEntries.size) {
                        Clickable(title = "显示剩余 ${entries.size - shownEntries.size} 个项目") { showAllEntries = true }
                    }
                    Clickable(enabled = !busy && chosen.isNotEmpty(), title = if (busy) busyLabel else "下载所选项目以继续恢复") {
                        scope.launch {
                            busy = true
                            busyLabel = "正在下载所选备份…"
                            try {
                                val result = cloudRepo.restoreAvailable(backup, chosen)
                                missing = result.unavailableEntries + result.missing.map { it.relativePath }.distinct()
                                unverifiablePlatforms = result.unverifiablePlatforms
                                val selectedFolder = cloudRepo.selectedRestoreFolder(result.folder, entries, chosen)
                                busyLabel = "正在索引下载内容…"
                                val targets = viewModel.indexCloudSnapshot(selectedFolder)
                                val selectedTargets = entries.filter { it.path in chosen }.map { if (it.kind == "app") Target.Apps else Target.Files }.distinct()
                                selectedTargets.filter { it in targets }.forEach { target ->
                                    navController.navigateSingle(MainRoutes.List.getRoute(target, OpType.RESTORE, backupDir = selectedFolder.canonicalPath.encodeURL()))
                                }
                                error = null
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "下载云盘备份失败"
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
                return@Column
            }

            AnimatedVisibility(uiState.storageIndex == 1) {
                if (accounts.isEmpty()) {
                    Clickable(
                        title = stringResource(id = R.string.account),
                        value = stringResource(id = R.string.no_available_account),
                        leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_cancel_circle),
                        trailingIcon = Icons.Rounded.KeyboardArrowRight,
                    ) {
                        navController.navigateSingle(MainRoutes.Cloud.route)
                    }
                } else {
                    val dialogState = LocalSlotScope.current!!.dialogSlot
                    var currentIndex by remember { mutableIntStateOf(if (uiState.cloudEntity == null) 0 else accounts.indexOfFirst { it.title == uiState.cloudEntity!!.name }) }
                    LaunchedEffect(currentIndex) {
                        viewModel.emitIntentOnIO(IndexUiIntent.SetCloudEntity(name = accounts[currentIndex].title))
                    }
                    Selectable(
                        title = stringResource(id = R.string.account),
                        leadingIcon = uiState.cloudEntity?.type?.icon ?: ImageVector.vectorResource(id = R.drawable.ic_rounded_person),
                        value = if (uiState.cloudEntity == null) stringResource(id = R.string.choose_an_account) else accounts[currentIndex].desc
                            ?: stringResource(id = R.string.unknown),
                        current = if (uiState.cloudEntity == null) stringResource(id = R.string.not_selected) else accounts[currentIndex].title
                    ) {
                        viewModel.launchOnIO {
                            val (state, selectedIndex) = dialogState.select(
                                title = context.getString(R.string.account),
                                defIndex = currentIndex,
                                items = accounts
                            )
                            if (state.isConfirm) {
                                currentIndex = selectedIndex
                            }
                        }
                    }
                }
            }

            val appsInteractionSource = remember { MutableInteractionSource() }
            Clickable(
                title = stringResource(id = R.string.apps),
                value = if (uiState.packages.isEmpty()) null else
                    "${context.getString(R.string.args_apps_backed_up, uiState.packages.size)}${if (uiState.packagesSize.isNotEmpty()) " (${uiState.packagesSize})" else ""}",
                leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_apps),
                interactionSource = appsInteractionSource,
                content = if (uiState.packages.isEmpty()) null else {
                    {
                        PackageIcons(modifier = Modifier.paddingTop(SizeTokens.Level8), packages = uiState.packages)
                    }
                }
            ) {
                viewModel.emitIntentOnIO(IndexUiIntent.ToAppList(navController))
            }

            val filesInteractionSource = remember { MutableInteractionSource() }
            Clickable(
                title = stringResource(id = R.string.files),
                value = if (uiState.medium.isEmpty()) null else
                    "${context.getString(R.string.args_files_backed_up, uiState.medium.size)}${if (uiState.mediumSize.isNotEmpty()) " (${uiState.mediumSize})" else ""}"
                ,
                leadingIcon = ImageVector.vectorResource(id = R.drawable.ic_rounded_folder_open),
                interactionSource = filesInteractionSource,
            ) {
                viewModel.emitIntentOnIO(IndexUiIntent.ToFileList(navController))
            }

            Title(title = stringResource(id = R.string.advanced)) {
                Clickable(
                    title = stringResource(id = R.string.reload),
                    value = stringResource(id = R.string.reload_desc),
                    leadingIcon = Icons.Rounded.ManageSearch,
                ) {
                    viewModel.emitIntentOnIO(IndexUiIntent.ToReload(navController))
                }
            }
        }
    }
}
