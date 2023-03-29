package com.xayah.databackup.ui.activity.processing.action

import android.content.Context
import android.graphics.Bitmap
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.mutableStateOf
import androidx.core.graphics.drawable.toBitmap
import com.xayah.databackup.App
import com.xayah.databackup.R
import com.xayah.databackup.data.*
import com.xayah.databackup.librootservice.RootService
import com.xayah.databackup.ui.activity.processing.ProcessingViewModel
import com.xayah.databackup.ui.activity.processing.components.ProcessObjectItem
import com.xayah.databackup.ui.activity.processing.components.ProcessingTask
import com.xayah.databackup.ui.activity.processing.components.onInfoUpdate
import com.xayah.databackup.util.*
import com.xayah.databackup.util.command.Command
import com.xayah.databackup.util.command.Preparation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

fun onBackupAppProcessing(viewModel: ProcessingViewModel, context: Context, globalObject: GlobalObject, retry: Boolean = false) {
    if (viewModel.isFirst.value) {
        viewModel.isFirst.value = false
        CoroutineScope(Dispatchers.IO).launch {
            val tag = "# BackupApp #"
            Logcat.getInstance().actionLogAddLine(tag, "===========${tag}===========")

            val loadingState = viewModel.loadingState
            val progress = viewModel.progress
            val topBarTitle = viewModel.topBarTitle
            val taskList = viewModel.taskList.value
            val objectList = viewModel.objectList.value.apply {
                clear()
                addAll(listOf(DataType.APK, DataType.USER, DataType.USER_DE, DataType.DATA, DataType.OBB).map {
                    ProcessObjectItem(type = it)
                })
            }
            val allDone = viewModel.allDone

            // Check global map
            if (globalObject.appInfoBackupMap.value.isEmpty()) {
                globalObject.appInfoBackupMap.emit(Command.getAppInfoBackupMap())
            }
            if (globalObject.appInfoRestoreMap.value.isEmpty()) {
                globalObject.appInfoRestoreMap.emit(Command.getAppInfoRestoreMap())
            }
            Logcat.getInstance().actionLogAddLine(tag, "Global map check finished.")

            if (retry.not()) {
                // Add processing tasks
                taskList.addAll(globalObject.appInfoBackupMap.value.values.toList()
                    .filter { it.isOnThisDevice && (it.selectApp.value || it.selectData.value) }
                    .map {
                        ProcessingTask(
                            appName = it.detailBase.appName,
                            packageName = it.detailBase.packageName,
                            appIcon = it.detailBase.appIcon ?: AppCompatResources.getDrawable(context, R.drawable.ic_round_android),
                            selectApp = it.selectApp.value,
                            selectData = it.selectData.value,
                            objectList = listOf()
                        )
                    })
            } else {
                Logcat.getInstance().actionLogAddLine(tag, "Retrying.")
            }

            Logcat.getInstance().actionLogAddLine(tag, "Task added, size: ${taskList.size}.")

            /**
             * Somehow the keyboards and accessibility services
             * will be changed after backing up on some devices,
             * so we restore them manually.
             */
            val keyboard = Preparation.getKeyboard()
            val services = Preparation.getAccessibilityServices()

            Logcat.getInstance().actionLogAddLine(tag, "keyboard: ${keyboard}, services: ${services}.")

            // Backup itself
            if (App.globalContext.readIsBackupItself()) {
                val isSuccess = Command.backupItself("com.xayah.databackup", App.globalContext.readBackupSavePath(), App.globalContext.readBackupUser())
                Logcat.getInstance().actionLogAddLine(tag, "Copy com.xayah.databackup to out: ${isSuccess}.")
            }


            val date = if (App.globalContext.readBackupStrategy() == BackupStrategy.Cover) GlobalString.cover else App.getTimeStamp()
            val userId = App.globalContext.readBackupUser()
            val compressionType = CompressionType.of(App.globalContext.readCompressionType())
            val compatibleMode = App.globalContext.readCompatibleMode()

            Logcat.getInstance().actionLogAddLine(tag, "Timestamp: ${date}.")
            Logcat.getInstance().actionLogAddLine(tag, "Date: ${Command.getDate(date)}.")
            Logcat.getInstance().actionLogAddLine(tag, "userId: ${userId}.")
            Logcat.getInstance().actionLogAddLine(tag, "CompressionType: ${compressionType.type}.")

            // Early stage finished
            loadingState.value = LoadingState.Success
            topBarTitle.value = "${context.getString(R.string.backuping)}(${progress.value}/${taskList.size})"
            for ((index, i) in taskList.withIndex()) {
                // Skip while retrying not-failed task
                if (retry && i.taskState.value != TaskState.Failed) continue

                // Reset object list
                for (j in objectList) {
                    j.apply {
                        state.value = TaskState.Waiting
                        title.value = GlobalString.ready
                        visible.value = false
                        subtitle.value = GlobalString.pleaseWait
                    }
                }

                // Enter processing state
                i.taskState.value = TaskState.Processing
                val appInfoBackup = globalObject.appInfoBackupMap.value[i.packageName]!!

                // Scroll to processing task
                if (viewModel.listStateIsInitialized) {
                    if (viewModel.scopeIsInitialized) {
                        viewModel.scope.launch {
                            viewModel.listState.animateScrollToItem(index)
                        }
                    }
                }

                var isSuccess = true
                val packageName = i.packageName
                val outPutPath = "${Path.getBackupDataSavePath()}/${packageName}/$date"
                val outPutIconPath =
                    "${Path.getBackupDataSavePath()}/${packageName}/icon.png"
                val userPath = "${Path.getUserPath()}/${packageName}"
                val userDePath = "${Path.getUserDePath()}/${packageName}"
                val dataPath = "${Path.getDataPath()}/${packageName}"
                val obbPath = "${Path.getObbPath()}/${packageName}"

                Logcat.getInstance().actionLogAddLine(tag, "AppName: ${i.appName}.")
                Logcat.getInstance().actionLogAddLine(tag, "PackageName: ${i.packageName}.")

                if (i.selectApp) {
                    objectList[0].visible.value = true
                }
                if (i.selectData) {
                    // USER is required in any case
                    objectList[1].visible.value = true

                    // Detect the existence of USER_DE
                    if (RootService.getInstance().exists(userDePath)) {
                        objectList[2].visible.value = true
                    }

                    // Detect the existence of DATA
                    if (RootService.getInstance().exists(dataPath)) {
                        objectList[3].visible.value = true
                    }

                    // Detect the existence of OBB
                    if (RootService.getInstance().exists(obbPath)) {
                        objectList[4].visible.value = true
                    }
                }

                // Suspend the app to avoid files changing
                RootService.getInstance().setPackagesSuspended(arrayOf(packageName), true)
                for (j in objectList) {
                    if (viewModel.isCancel.value) break
                    if (j.visible.value) {
                        j.state.value = TaskState.Processing
                        when (j.type) {
                            DataType.APK -> {
                                Command.compressAPK(compressionType, packageName, outPutPath, userId, appInfoBackup.detailBackup.appSize, compatibleMode)
                                { type, line ->
                                    onInfoUpdate(type, line ?: "", j)
                                }.apply {
                                    if (!this) {
                                        isSuccess = false
                                    } else {
                                        // Save the size of apk
                                        val paths = RootService.getInstance().displayPackageFilePath(packageName, userId.toInt())
                                        if (paths.isNotEmpty()) {
                                            appInfoBackup.detailBackup.appSize = RootService.getInstance().countSize(Path.getParentPath(paths[0]), ".*(.apk)").toString()
                                        } else {
                                            Logcat.getInstance().actionLogAddLine(tag, "Failed to get $packageName APK path.")
                                        }
                                    }
                                }
                            }
                            DataType.USER -> {
                                Command.compress(compressionType, DataType.USER, packageName, outPutPath, Path.getUserPath(), appInfoBackup.detailBackup.userSize, compatibleMode)
                                { type, line ->
                                    onInfoUpdate(type, line ?: "", j)
                                }.apply {
                                    if (!this) {
                                        isSuccess = false
                                    } else {
                                        // Save the size of user
                                        appInfoBackup.detailBackup.userSize = RootService.getInstance().countSize(userPath).toString()
                                    }
                                }
                            }
                            DataType.USER_DE -> {
                                Command.compress(compressionType, DataType.USER_DE, packageName, outPutPath, Path.getUserDePath(), appInfoBackup.detailBackup.userDeSize, compatibleMode)
                                { type, line ->
                                    onInfoUpdate(type, line ?: "", j)
                                }.apply {
                                    if (!this) {
                                        isSuccess = false
                                    } else {
                                        // Save the size of user_de
                                        appInfoBackup.detailBackup.userDeSize = RootService.getInstance().countSize(userDePath).toString()
                                    }
                                }
                            }
                            DataType.DATA -> {
                                Command.compress(compressionType, DataType.DATA, packageName, outPutPath, Path.getDataPath(), appInfoBackup.detailBackup.dataSize, compatibleMode)
                                { type, line ->
                                    onInfoUpdate(type, line ?: "", j)
                                }.apply {
                                    if (!this) {
                                        isSuccess = false
                                    } else {
                                        // Save the size of data
                                        appInfoBackup.detailBackup.dataSize = RootService.getInstance().countSize(dataPath).toString()
                                    }
                                }
                            }
                            DataType.OBB -> {
                                Command.compress(compressionType, DataType.OBB, packageName, outPutPath, Path.getObbPath(), appInfoBackup.detailBackup.obbSize, compatibleMode)
                                { type, line ->
                                    onInfoUpdate(type, line ?: "", j)
                                }.apply {
                                    if (!this) {
                                        isSuccess = false
                                    } else {
                                        // Save the size of obb
                                        appInfoBackup.detailBackup.obbSize = RootService.getInstance().countSize(obbPath).toString()
                                    }
                                }
                            }
                            else -> {}
                        }
                    }
                }
                // Unsuspend the app
                RootService.getInstance().setPackagesSuspended(arrayOf(packageName), false)
                if (viewModel.isCancel.value) break

                appInfoBackup.detailBackup.date = date
                // Save icon
                if (App.globalContext.readIsBackupIcon()) {
                    withContext(Dispatchers.IO) {
                        Logcat.getInstance().actionLogAddLine(tag, "Trying to save icon.")
                        var byteArray = ByteArray(0)
                        try {
                            val byteArrayOutputStream = ByteArrayOutputStream()
                            appInfoBackup.detailBase.appIcon?.toBitmap()?.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream)
                            byteArray = byteArrayOutputStream.toByteArray()
                            byteArrayOutputStream.flush()
                            byteArrayOutputStream.close()
                            RootService.getInstance().writeBytesByDescriptor(outPutIconPath, byteArray)
                            Logcat.getInstance().actionLogAddLine(tag, "Icon saved successfully: ${byteArray.size}")
                        } catch (_: Exception) {
                            Logcat.getInstance().actionLogAddLine(tag, "Icon is too large to save: ${byteArray.size}")
                        }
                    }
                }

                if (isSuccess) {
                    val detail = AppInfoDetailRestore().apply {
                        this.selectApp.value = false
                        this.selectData.value = false
                        this.hasApp.value = appInfoBackup.selectApp.value
                        this.hasData.value = appInfoBackup.selectData.value
                        this.versionName = appInfoBackup.detailBackup.versionName
                        this.versionCode = appInfoBackup.detailBackup.versionCode
                        this.appSize = appInfoBackup.detailBackup.appSize
                        this.userSize = appInfoBackup.detailBackup.userSize
                        this.userDeSize = appInfoBackup.detailBackup.userDeSize
                        this.dataSize = appInfoBackup.detailBackup.dataSize
                        this.obbSize = appInfoBackup.detailBackup.obbSize
                        this.date = appInfoBackup.detailBackup.date
                    }
                    if (globalObject.appInfoRestoreMap.value.containsKey(packageName).not()) {
                        globalObject.appInfoRestoreMap.value[packageName] = AppInfoRestore()
                    }
                    val appInfoRestore =
                        globalObject.appInfoRestoreMap.value[packageName]!!.apply {
                            this.detailBase = appInfoBackup.detailBase
                            this.firstInstallTime = appInfoBackup.firstInstallTime
                        }

                    val itemIndex =
                        appInfoRestore.detailRestoreList.indexOfFirst { date == it.date }
                    if (itemIndex == -1) {
                        appInfoRestore.detailRestoreList.add(detail)
                        appInfoRestore.restoreIndex++
                    } else {
                        appInfoRestore.detailRestoreList[itemIndex] = detail
                    }
                }

                i.apply {
                    this.taskState.value = if (isSuccess) TaskState.Success else TaskState.Failed
                    val list = mutableListOf<ProcessObjectItem>()
                    for (j in objectList) {
                        list.add(
                            ProcessObjectItem(
                                state = mutableStateOf(j.state.value),
                                visible = mutableStateOf(j.visible.value),
                                title = mutableStateOf(j.title.value),
                                subtitle = mutableStateOf(j.subtitle.value),
                                type = j.type,
                            )
                        )
                    }
                    this.objectList = list.toList()
                }

                progress.value += 1
                topBarTitle.value = "${context.getString(R.string.backuping)}(${progress.value}/${taskList.size})"
            }

            // Restore the keyboards and accessibility services
            keyboard.apply {
                if (this.first) Preparation.setKeyboard(this.second)
            }
            services.apply {
                if (this.first) Preparation.setAccessibilityServices(this.second)
            }
            Logcat.getInstance().actionLogAddLine(tag, "Restore keyboard and services.")

            // Save lists
            GsonUtil.saveAppInfoBackupMapToFile(globalObject.appInfoBackupMap.value)
            GsonUtil.saveAppInfoRestoreMapToFile(globalObject.appInfoRestoreMap.value)
            globalObject.appInfoRestoreMap.value.clear()
            Logcat.getInstance().actionLogAddLine(tag, "Save global map.")
            topBarTitle.value = "${context.getString(R.string.backup_finished)}!"
            allDone.targetState = true
            Logcat.getInstance().actionLogAddLine(tag, "===========${tag}===========")
        }
    }
}
