package com.mikyou.plugins.image.slimming.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.mikyou.plugins.image.slimming.extension.showDialog
import com.mikyou.plugins.image.slimming.ui.ImageSlimmingDialog
import com.mikyou.plugins.image.slimming.ui.InputKeyDialog
import com.mikyou.plugins.image.slimming.ui.model.ImageSlimmingModel
import com.tinify.Tinify
import java.io.File
import java.nio.charset.Charset


class ImageSlimmingAction : AnAction() {
    //插件入口函数
    override fun actionPerformed(event: AnActionEvent?) = with(File(FILE_PATH_API_KEY)) {
        if (!exists() || readText(Charset.defaultCharset()).isBlank()) {//保存API_KEY的文件不存在, 或者读取的文件内容为空，提示用户输入(一般为第一次)
            popupInputKeyDialog(labelTitle = "请输入TinyPng Key, 请往TinyPng官网申请", event = event)
        } else {//为了减少不必要API KEY检查次数，此处改为只要文件存在，直接读取文件中的API_KEY
            Tinify.setKey(readText(Charset.defaultCharset()))//(注意:此时API_KEY有可能不合法，此处先不做检验，而是去捕获认证异常，提示用户重新键入API_KEY)
            popupCompressDialog(event)
        }
    }

    //检查Key的合法性
    private fun checkKeyValid(event: AnActionEvent?, apiKey: String) {
        if (apiKey.isBlank()) {
            popupInputKeyDialog(labelTitle = "TinyPng key验证失败，请重新输入", event = event)
            return
        }
        getEventProject(event)?.asyncTask(hintText = "正在检查key是否合法", runAction = {
            try {
                Tinify.setKey(apiKey)
                Tinify.validate()
            } catch (exception: Exception) {
                throw exception
            }
        }, successAction = {
            updateExpireKey(apiKey)
            popupCompressDialog(event)
        }, failAction = {
            println("验证Key失败!!${it.message}")
            popupInputKeyDialog(labelTitle = "TinyPng key验证失败，请重新输入", event = event)
        })
    }

    //更新本地缓存中过期或者失效的API_KEY
    private fun updateExpireKey(apiKey: String) = File(FILE_PATH_API_KEY).createFile {
        if (it.readText(Charset.defaultCharset()) != apiKey) {
            it.writeText(apiKey, Charset.defaultCharset())
        }
    }

    //弹出输入apiKey dialog
    private fun popupInputKeyDialog(labelTitle: String, event: AnActionEvent?) = InputKeyDialog(labelTitle, object : InputKeyDialog.DialogCallback {
        override fun onOkBtnClicked(tinyPngKey: String) {
            checkKeyValid(event, tinyPngKey ?: "")
        }

        override fun onCancelBtnClicked() {

        }
    }).showDialog(width = 530, height = 150, isInCenter = true, isResizable = false)

    //弹出压缩目录选择 dialog
    private fun popupCompressDialog(event: AnActionEvent?) = ImageSlimmingDialog(readUsedDirs(), readUsedFilePrefix(), object : ImageSlimmingDialog.DialogCallback {
        override fun onOkClicked(imageSlimmingModel: ImageSlimmingModel) {
            saveUsedDirs(imageSlimmingModel)
            saveUsedFilePrefix(imageSlimmingModel.filePrefix)
            val inputFiles: List<File> = readInputDirFiles(imageSlimmingModel.inputDir)
            val startTime = System.currentTimeMillis()
            getEventProject(event)?.asyncTask(hintText = "正在压缩", runAction = {
                executeCompressPic(inputFiles, imageSlimmingModel)
            }, successAction = {
                Messages.showWarningDialog("压缩完成, 已压缩: ${inputFiles.size}张图片, 压缩总时长共计: ${(System.currentTimeMillis() - startTime) / 1000}s", "来自ImageSlimming提示")
            }, failAction = {
                //由于之前并没有对文件中的API_KEY提前做有效性检验，所以此处需要捕获API_KEY认证的异常:
                if (it.message?.contains("Bad authorization") == true || it.message?.contains("HTTP 400/Bad request") == true) {
                    popupInputKeyDialog(labelTitle = "TinyPng key存在异常，请重新输入", event = event)
                }
            })
        }

        override fun onCancelClicked() {

        }

    }).showDialog(width = 530, height = 200, isInCenter = true, isResizable = false)

    //读取本地缓存了用户使用过的输入，输出目录的文件
    private fun readUsedDirs(): Pair<List<String>, List<String>> {
        val inputDirStrs = mutableListOf<String>()
        val outputDirStrs = mutableListOf<String>()
        val inputDirsFile = File(FILE_PATH_INPUT_DIRS)
        val outputDirsFile = File(FILE_PATH_OUTPUT_DIRS)

        if (inputDirsFile.exists()) {
            inputDirStrs.addAll(inputDirsFile.readLines(Charset.defaultCharset()))
        }

        if (outputDirsFile.exists()) {
            outputDirStrs.addAll(outputDirsFile.readLines(Charset.defaultCharset()))
        }

        return inputDirStrs to outputDirStrs
    }

    //读取本地缓存了用户使用过的文件前缀
    private fun readUsedFilePrefix(): List<String> = with(File(FILE_PATH_PREFIX)) {
        return if (exists()) {
            readLines(Charset.defaultCharset())
        } else {
            listOf()
        }
    }

    //写入用户当前使用的文件前缀到缓存文件中
    private fun saveUsedFilePrefix(filePrefix: String) = File(FILE_PATH_PREFIX).createFile {
        if (!it.readLines(Charset.defaultCharset()).contains(filePrefix)) {
            it.appendText("$filePrefix\n", Charset.defaultCharset())
        }
    }

    //写入用户当前使用的输入、输出路径到缓存文件中
    private fun saveUsedDirs(imageSlimmingModel: ImageSlimmingModel) {

        File(FILE_PATH_INPUT_DIRS).createFile {
            if (!it.readLines(Charset.defaultCharset()).contains(imageSlimmingModel.inputDir)) {
                it.appendText("${imageSlimmingModel.inputDir}\n", Charset.defaultCharset())
            }
        }

        File(FILE_PATH_OUTPUT_DIRS).createFile {
            if (!it.readLines(Charset.defaultCharset()).contains(imageSlimmingModel.outputDir)) {
                it.appendText("${imageSlimmingModel.outputDir}\n", Charset.defaultCharset())
            }
        }
    }

    //执行图片压缩操作
    private fun executeCompressPic(inputFiles: List<File>, imageSlimmingModel: ImageSlimmingModel) = inputFiles.forEach {
        if (imageSlimmingModel.filePrefix.isBlank()) {
            Tinify.fromFile(it.absolutePath).toFile("${imageSlimmingModel.outputDir}/${it.name}")
        } else {
            Tinify.fromFile(it.absolutePath).toFile("${imageSlimmingModel.outputDir}/${imageSlimmingModel.filePrefix}_${it.name}")
        }
    }

    //读取用户输入目录下的所有图片文件
    private fun readInputDirFiles(inputDir: String): List<File> {
        val inputFiles: List<String> = inputDir.split(",")
        if (inputFiles.isEmpty()) {
            return listOf()
        }

        if (inputFiles.size == 1) {
            val inputFile: File = File(inputFiles[0])
            if (inputFile.isFile) {
                return listOf(inputFile).filterPic()
            }

            if (inputFile.isDirectory) {
                return inputFile.listFiles().toList().filterPic()
            }
        }

        return inputFiles.map { File(it) }.filterPic()
    }

    //筛选图片后缀的文件List<File>的扩展函数filterPic
    private fun List<File>.filterPic(): List<File> = this.filter { it.name.endsWith(".png") || it.name.endsWith(".jpg") || it.name.endsWith(".jpeg") }

    //创建后台异步任务的Project的扩展函数asyncTask
    private fun Project.asyncTask(hintText: String, runAction: (ProgressIndicator) -> Unit, successAction: (() -> Unit)? = null, failAction: ((Throwable) -> Unit)? = null, finishAction: (() -> Unit)? = null) {
        object : Task.Backgroundable(this, hintText) {
            override fun run(p0: ProgressIndicator) {
                runAction.invoke(p0)
            }

            override fun onSuccess() {
                successAction?.invoke()
            }

            override fun onThrowable(error: Throwable) {
                failAction?.invoke(error)
            }

            override fun onFinished() {
                finishAction?.invoke()
            }
        }.queue()
    }

    //创建文件扩展函数
    private fun File.createFile(existedAction: ((File) -> Unit)? = null) {
        if (!exists()) {//文件不存在,创建一个新文件
            File(parent).mkdir()
            createNewFile()
        }
        //文件存在
        existedAction?.invoke(this)
    }

    companion object {
        private var FILE_PATH_API_KEY = "${PathManager.getPreInstalledPluginsPath()}/ImageSlimming/apiKey.csv"
        private var FILE_PATH_INPUT_DIRS = "${PathManager.getPreInstalledPluginsPath()}/ImageSlimming/inputDirs.csv"
        private var FILE_PATH_OUTPUT_DIRS = "${PathManager.getPreInstalledPluginsPath()}/ImageSlimming/outputDirs.csv"
        private var FILE_PATH_PREFIX = "${PathManager.getPreInstalledPluginsPath()}/ImageSlimming/prefix.csv"
    }
}