package com.xayah.core.service.packages.backup

import com.xayah.core.data.cloudpool.CloudBackupRepository
import com.xayah.core.data.cloudpool.CloudBackupRequest
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.model.OperationState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import com.xayah.core.model.util.get
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.PackageDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.DataType
import com.xayah.core.model.OpType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.PackageEntity
import com.xayah.core.model.database.TaskDetailPackageEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.PackagesBackupUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
internal class BackupServiceLocalImpl @Inject constructor() : AbstractBackupService() {
    override val mTAG: String = "BackupServiceLocalImpl"

    @Inject
    override lateinit var mRootService: RemoteRootService

    @Inject
    override lateinit var mPathUtil: PathUtil

    @Inject
    override lateinit var mCommonBackupUtil: CommonBackupUtil

    @Inject
    override lateinit var mTaskDao: TaskDao

    @Inject
    override lateinit var mTaskRepo: TaskRepository

    override val mTaskEntity by lazy {
        TaskEntity(
            id = 0,
            opType = OpType.BACKUP,
            taskType = TaskType.PACKAGE,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override suspend fun backup(type: DataType, p: PackageEntity, r: PackageEntity?, t: TaskDetailPackageEntity, dstDir: String) {
        if (cloudRequested && cloudFailed) {
            t.update(dataType = type, state = OperationState.ERROR, log = "Cloud backup stopped after an earlier failure")
            t.update(dataType = type, progress = 1f)
            t.update(processingIndex = t.processingIndex + 1)
            return
        }
        val result = if (type == DataType.PACKAGE_APK) {
            mPackagesBackupUtil.backupApk(p = p, t = t, r = r, dstDir = dstDir)
        } else {
            mPackagesBackupUtil.backupData(p = p, t = t, r = r, dataType = type, dstDir = dstDir)
        }
        if (cloudRequested && result.isSuccess && t.get(type).state != OperationState.SKIP) {
            t.update(dataType = type, state = OperationState.UPLOADING)
            try {
                uploadCurrent(mPackageRepo.getArchiveDst(dstDir, type, p.indexInfo.compressionType))
                t.update(dataType = type, state = OperationState.DONE)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                cloudFailed = true
                log { "CLOUD_UPLOAD_FAILED[$type]: ${error.javaClass.name}: ${error.message}" }
                t.update(dataType = type, state = OperationState.ERROR, log = "Cloud upload failed: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
        t.update(dataType = type, progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    @Inject
    override lateinit var mPackageDao: PackageDao

    @Inject
    override lateinit var mPackageRepo: PackageRepository

    @Inject
    override lateinit var mPackagesBackupUtil: PackagesBackupUtil

    override val registerLocalRestore: Boolean get() = !cloudRequested
    private var cloudRequested = false
    private var session: CloudBackupRepository.UploadSession? = null
    private var cloudFailed = false

    override fun beforeInitialize() {
        cloudRequested = CloudBackupRequest.take(CloudBackupRequest.Kind.APPS)
    }

    override suspend fun onTargetDirsCreated() {
        if (!cloudRequested) return
        runCatching { CloudBackupRepository(mContext).begin() }
            .onSuccess { session = it }
            .onFailure {
                cloudFailed = true
                log { "CLOUD_SESSION_FAILED: ${it.javaClass.name}: ${it.message}" }
            }
    }

    private suspend fun uploadCurrent(path: String) {
        val writer = checkNotNull(session) { "Cloud session is not ready" }
        val root = File(mRootDir)
        val file = File(path)
        requireArchiveInStaging(root, file)
        if (!file.canRead()) mRootService.setAllPermissions(path).getOrThrow()
        writer.append(file, file.relativeTo(root).invariantSeparatorsPath) {
            // Release only the finalized staging archive after its remote object is confirmed.
            check(mRootService.deleteRecursively(file.path)) { "Could not release cloud backup staging file" }
        }
    }

    private fun requireArchiveInStaging(root: File, file: File) {
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
    }

    override suspend fun onConfigSaved(path: String, archivesRelativeDir: String) {
        if (!cloudRequested || cloudFailed) return
        try {
            uploadCurrent(path)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            cloudFailed = true
            log { "CLOUD_UPLOAD_FAILED: ${error.javaClass.name}: ${error.message}" }
        }
    }

    override fun onDestroy() {
        cloudRequested = false
        session = null
        super.onDestroy()
    }

    override suspend fun clear() {
        if (!cloudRequested) return
        try {
            check(!cloudFailed && mTaskEntity.failureCount == 0) { "Cloud backup incomplete" }
            val result = checkNotNull(session).finish()
            check(result.missingCopies.isEmpty()) { "Not all cloud recovery indexes could be uploaded" }
            require(File(mRootDir).name.startsWith("cloud-work-")) { "Refusing to clean non-staging directory" }
            check(mRootService.deleteRecursively(mRootDir)) { "Could not clean cloud staging" }
        } finally {
            session = null
        }
    }

    override val mRootDir by lazy {
        if (!cloudRequested) mContext.localBackupSaveDir()
        else File(mContext.filesDir, "cloud-work-${UUID.randomUUID()}").path
    }
    override val mAppsDir by lazy { "$mRootDir/apps" }
    override val mConfigsDir by lazy { "$mRootDir/configs" }
}
