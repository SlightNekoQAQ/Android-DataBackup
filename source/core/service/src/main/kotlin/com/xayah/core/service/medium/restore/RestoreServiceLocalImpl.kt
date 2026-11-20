package com.xayah.core.service.medium.restore

import com.xayah.core.data.cloudpool.CloudBackupRepository
import com.xayah.core.data.repository.MediaRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import com.xayah.core.data.repository.TaskRepository
import com.xayah.core.database.dao.MediaDao
import com.xayah.core.database.dao.TaskDao
import com.xayah.core.model.OpType
import com.xayah.core.model.OperationState
import com.xayah.core.model.TaskType
import com.xayah.core.model.database.MediaEntity
import com.xayah.core.model.database.TaskDetailMediaEntity
import com.xayah.core.model.database.TaskEntity
import com.xayah.core.rootservice.service.RemoteRootService
import com.xayah.core.service.util.CommonBackupUtil
import com.xayah.core.service.util.MediumRestoreUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.util.localBackupSaveDir
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
internal class RestoreServiceLocalImpl @Inject constructor() : AbstractRestoreService() {
    override val mTAG: String = "RestoreServiceLocalImpl"

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
            opType = OpType.RESTORE,
            taskType = TaskType.MEDIA,
            startTimestamp = mStartTimestamp,
            endTimestamp = mEndTimestamp,
            backupDir = mRootDir,
            isProcessing = true,
        )
    }

    private var cloudPlan: CloudBackupRepository.RestorePlan? = null

    override suspend fun getMedium(): List<MediaEntity> {
        cloudPlan = CloudBackupRepository(mContext).openRestorePlan(File(mRootDir))
        return mMediaRepo.queryActivated(OpType.RESTORE, "", mRootDir)
    }

    override suspend fun restore(m: MediaEntity, t: TaskDetailMediaEntity, srcDir: String) {
        if (m.path.isEmpty()) {
            t.update(state = OperationState.ERROR, log = "Path is empty.")
        } else {
            val plan = cloudPlan
            if (plan == null) mMediumRestoreUtil.restoreMedia(m = m, t = t, srcDir = srcDir)
            else try {
                t.update(state = OperationState.DOWNLOADING)
                val archive = mMediaRepo.getArchiveDst(srcDir, m.indexInfo.compressionType)
                plan.withArchive(archive) { mMediumRestoreUtil.restoreMedia(m = m, t = t, srcDir = srcDir) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                t.update(state = OperationState.ERROR, log = error.message ?: "Cloud restore failed")
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
    override lateinit var mMediumRestoreUtil: MediumRestoreUtil

    var backupRoot: String? = null

    override val mRootDir by lazy { backupRoot ?: mContext.localBackupSaveDir() }
    override val mFilesDir by lazy { "$mRootDir/${PathUtil.getFilesRelativeDir()}" }
    override val mConfigsDir by lazy { "$mRootDir/${PathUtil.getConfigsRelativeDir()}" }
}
