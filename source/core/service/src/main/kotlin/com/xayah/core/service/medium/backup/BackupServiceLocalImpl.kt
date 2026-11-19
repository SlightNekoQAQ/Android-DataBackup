package com.xayah.core.service.medium.backup

import com.xayah.core.data.cloudpool.CloudBackupRepository
import com.xayah.core.data.cloudpool.CloudBackupRequest
import com.xayah.core.data.repository.MediaRepository
import com.xayah.core.model.OperationState
import java.io.File
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
import com.xayah.core.util.NotificationUtil
import com.xayah.core.util.PathUtil
import com.xayah.core.service.R
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
        mMediumBackupUtil.backupMedia(m = m, t = t, r = r, dstDir = dstDir)
        t.update(progress = 1f)
        t.update(processingIndex = t.processingIndex + 1)
    }

    @Inject
    override lateinit var mMediaDao: MediaDao

    @Inject
    override lateinit var mMediaRepo: MediaRepository

    @Inject
    override lateinit var mMediumBackupUtil: MediumBackupUtil

    private var cloudPassword: CharArray? = null

    override suspend fun onTargetDirsCreated() {
        cloudPassword = CloudBackupRequest.take(CloudBackupRequest.Kind.FILES)
    }

    override fun onDestroy() {
        cloudPassword?.fill('\u0000')
        cloudPassword = null
        super.onDestroy()
    }

    override suspend fun clear() {
        val password = cloudPassword ?: return
        cloudPassword = null
        try {
            val folders = mMediaEntities.filter { it.state == OperationState.DONE }
                .map { "files/${it.mediaEntity.archivesRelativeDir}" }.toSet()
            require(folders.isNotEmpty()) { "No completed file backups to upload" }
            val result = CloudBackupRepository(mContext).publish(File(mRootDir), password, folders) { done, total ->
                NotificationUtil.notify(mContext, mNotificationBuilder, mContext.getString(R.string.backing_up),
                    "$done / $total", max = total, progress = done)
            }
            check(result.missingCopies.isEmpty()) { "Not all cloud recovery indexes could be uploaded" }
        } finally { password.fill('\u0000') }
    }

    override val mRootDir by lazy { mContext.localBackupSaveDir() }
    override val mFilesDir by lazy { mPathUtil.getLocalBackupFilesDir() }
    override val mConfigsDir by lazy { mPathUtil.getLocalBackupConfigsDir() }
}
