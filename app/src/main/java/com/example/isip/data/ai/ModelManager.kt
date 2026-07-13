package com.example.isip.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * AI模型管理器
 *
 * 负责模型的下载、验证、加载和管理
 */
class ModelManager(private val context: Context) {

    private val modelsDir: File by lazy {
        File(context.filesDir, "models").apply {
            if (!exists()) mkdirs()
        }
    }

    /**
     * 检查模型是否存在
     */
    fun isModelAvailable(modelName: String): Boolean {
        return getModelFile(modelName).exists() || isModelInAssets(modelName)
    }

    /**
     * 获取模型文件
     */
    fun getModelFile(modelName: String): File {
        return File(modelsDir, modelName)
    }

    /**
     * 检查模型是否在assets中
     */
    private fun isModelInAssets(modelName: String): Boolean {
        return try {
            val path = "models/$modelName"
            context.assets.open(path).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从assets复制模型到内部存储（可选，用于大模型）
     */
    suspend fun copyModelFromAssets(
        modelName: String,
        progressCallback: ((Int, Int) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val assetPath = "models/$modelName"
        val targetFile = getModelFile(modelName)

        if (targetFile.exists()) {
            Log.d(TAG, "模型已存在: ${targetFile.absolutePath}")
            return@withContext targetFile
        }

        Log.d(TAG, "开始复制模型: $assetPath")

        context.assets.open(assetPath).use { input ->
            val totalBytes = input.available()
            var copiedBytes = 0

            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    copiedBytes += read
                    progressCallback?.invoke(copiedBytes, totalBytes)
                }
            }
        }

        Log.d(TAG, "模型复制完成: ${targetFile.absolutePath}")
        targetFile
    }

    /**
     * 获取模型信息
     */
    fun getModelInfo(modelName: String): ModelInfo? {
        val file = getModelFile(modelName)
        if (!file.exists() && !isModelInAssets(modelName)) {
            return null
        }

        return ModelInfo(
            name = modelName,
            sizeBytes = if (file.exists()) file.length() else 0L,
            path = if (file.exists()) file.absolutePath else "assets/models/$modelName",
            isInAssets = isModelInAssets(modelName),
            isInStorage = file.exists()
        )
    }

    /**
     * 删除模型文件
     */
    fun deleteModel(modelName: String): Boolean {
        val file = getModelFile(modelName)
        return if (file.exists()) {
            file.delete().also {
                if (it) Log.d(TAG, "模型已删除: $modelName")
            }
        } else {
            false
        }
    }

    /**
     * 获取所有可用模型
     */
    fun listAvailableModels(): List<ModelInfo> {
        val models = mutableListOf<ModelInfo>()

        // 从assets读取
        try {
            context.assets.list("models")?.forEach { fileName ->
                models.add(ModelInfo(
                    name = fileName,
                    sizeBytes = 0L,
                    path = "assets/models/$fileName",
                    isInAssets = true,
                    isInStorage = false
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "无法读取assets/models目录", e)
        }

        // 从内部存储读取
        modelsDir.listFiles()?.forEach { file ->
            if (!models.any { it.name == file.name }) {
                models.add(ModelInfo(
                    name = file.name,
                    sizeBytes = file.length(),
                    path = file.absolutePath,
                    isInAssets = false,
                    isInStorage = true
                ))
            }
        }

        return models
    }

    /**
     * 清理所有模型缓存
     */
    fun clearAllModels(): Int {
        var count = 0
        modelsDir.listFiles()?.forEach { file ->
            if (file.delete()) count++
        }
        Log.d(TAG, "已清理 $count 个模型文件")
        return count
    }

    companion object {
        private const val TAG = "ModelManager"
        private const val BUFFER_SIZE = 8192

        @Volatile
        private var instance: ModelManager? = null

        fun getInstance(context: Context): ModelManager {
            return instance ?: synchronized(this) {
                instance ?: ModelManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}

/**
 * 模型信息
 */
data class ModelInfo(
    val name: String,
    val sizeBytes: Long,
    val path: String,
    val isInAssets: Boolean,
    val isInStorage: Boolean
) {
    val sizeMB: Float
        get() = sizeBytes / (1024f * 1024f)

    val sizeGB: Float
        get() = sizeBytes / (1024f * 1024f * 1024f)

    fun getReadableSize(): String = when {
        sizeBytes < 1024 -> "$sizeBytes B"
        sizeMB < 1024 -> "%.2f MB".format(sizeMB)
        else -> "%.2f GB".format(sizeGB)
    }
}
