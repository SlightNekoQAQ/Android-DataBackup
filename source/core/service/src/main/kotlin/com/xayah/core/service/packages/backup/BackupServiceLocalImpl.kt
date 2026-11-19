package com.xayah.core.service.packages.backup

import com.xayah.core.data.cloudpool.CloudBackupRepository
import com.xayah.core.data.cloudpool.CloudBackupRequest
import com.xayah.core.data.repository.PackageRepository
import com.xayah.core.model.OperationState
import java.io.File
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
        if (type == DataType.PACKAGE_APK) {
            mPackagesBackupUtil.backupApk(p = p, t = t, r = r, dstDir = dstDir)
        } else {
            mPackagesBackupUtil.backupData(p = p, t = t, r = r, dataType = type, dstDir = dstDir)
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

    private var cloudPassword: CharArray? = null

    override suspend fun onTargetDirsCreated() {
        cloudPassword = CloudBackupRequest.take(CloudBackupRequest.Kind.APPS)
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
            val folders = mPkgEntities.filter { it.state == OperationState.DONE }
                .map { "apps/${it.packageEntity.archivesRelativeDir}" }.toSet()
            require(folders.isNotEmpty()) { "No completed application backups to upload" }
            val result = CloudBackupRepository(mContext).publish(File(mRootDir), password, folders)
            check(result.missingCopies.isEmpty()) { "Not all cloud recovery indexes could be uploaded" }
        } finally { password.fill('\u0000') }
    }

    override val mRootDir by lazy { mContext.localBackupSaveDir() }
    override val mAppsDir by lazy { mPathUtil.getLocalBackupAppsDir() }
    override val mConfigsDir by lazy { mPathUtil.getLocalBackupConfigsDir() }
}
