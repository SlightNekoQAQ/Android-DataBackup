package com.xayah.core.service.medium.backup

import com.xayah.core.data.cloudpool.CloudBackupRepository
import com.xayah.core.data.cloudpool.CloudBackupRequest
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.model.OperationState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.OpType
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.MediumBackupUtil
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
            taskType = TaskType.MEDIA,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    override suspend fun backup(m: MediaEntity, r: MediaEntity?, t: TaskDetailMediaEntity, dstDir: String) {
        if (cloudRequested && cloudFailed) {
            t.update(state = OperationState.ERROR, log = "Cloud backup stopped after an earlier failure")
            t.update(progress = 1f)
            t.update(processingIndex = t.processingIndex + 1)
            return
        }
        val result = mMediumBackupUtil.backupMedia(m = m, t = t, r = r, dstDir = dstDir)
        if (cloudRequested && result.isSuccess && t.mediaInfo.state != OperationState.SKIP) {
            t.update(state = OperationState.UPLOADING)
            try {
                uploadCurrent(mMediaRepo.getArchiveDst(dstDir, m.indexInfo.compressionType))
                t.update(state = OperationState.DONE)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                cloudFailed = true
                t.update(state = OperationState.ERROR, log = "Cloud upload failed; temporary archive retained")
            }
        }
        t.update(progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    @Inject
    override lateinit var mMediaDao: MediaDao

    @Inject
    override lateinit var mMediaRepo: MediaRepository

    @Inject
    override lateinit var mMediumBackupUtil: MediumBackupUtil

    override val registerLocalRestore: Boolean get() = !cloudRequested
    private var cloudRequested = false
    private var session: CloudBackupRepository.UploadSession? = null
    private var cloudFailed = false

    override fun beforeInitialize() {
        cloudRequested = CloudBackupRequest.take(CloudBackupRequest.Kind.FILES)
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
    override val mFilesDir by lazy { "$mRootDir/files" }
    override val mConfigsDir by lazy { "$mRootDir/configs" }
}
