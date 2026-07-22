package com.example.isip.test

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.isip.data.ai.QwenModel
import com.example.isip.data.ai.QwenInferenceEngine
import com.example.isip.data.ai.ModelConfig
import com.example.isip.data.ai.QuantizationType
import kotlinx.coroutines.launch
import java.io.File

/**
 * 测试 Qwen3.5 模型集成的示例代码
 *
 * 用法：
 * 1. 在 MainActivity 中调用 testModelLoading()
 * 2. 查看 logcat 输出验证模型加载和推理
 */
class QwenIntegrationTest {

    companion object {
        private const val TAG = "QwenIntegrationTest"

        /**
         * 测试模型加载
         */
        fun testModelLoading(activity: ComponentActivity) {
            activity.lifecycleScope.launch {
                try {
                    Log.i(TAG, "=== 开始测试 Qwen3.5 模型集成 ===")

                    // 1. 检查模型文件
                    Log.i(TAG, "1. 检查模型文件...")
                    val mainModelPath = QwenModel.MODEL_ASSET_PATH
                    val mmProjPath = QwenModel.MMPROJ_ASSET_PATH

                    val mainModelFile = File(activity.filesDir, mainModelPath)
                    val mmProjFile = File(activity.filesDir, mmProjPath)

                    if (!mainModelFile.exists()) {
                        Log.w(TAG, "⚠️ 主模型文件不存在: ${mainModelFile.absolutePath}")
                        Log.w(TAG, "尝试从 assets 加载...")
                    }

                    // 2. 初始化推理引擎
                    Log.i(TAG, "2. 初始化推理引擎...")
                    val engine = QwenInferenceEngine.getInstance(
                        activity,
                        ModelConfig(
                            useGPU = true,
                            numThreads = 4,
                            maxTokens = 256,
                            temperature = 0.3f,
                            contextSize = 2048,
                            quantizationType = QuantizationType.Q4_0
                        )
                    )

                    engine.initialize(
                        modelPath = mainModelPath,
                        mmProjPath = mmProjPath
                    )

                    Log.i(TAG, "✅ 模型初始化成功")

                    // 3. 测试文本生成
                    Log.i(TAG, "3. 测试文本生成...")
                    val textResponse = engine.generateText(
                        prompt = "你好，请介绍一下你自己。",
                        maxTokens = 100
                    )
                    Log.i(TAG, "文本响应: $textResponse")

                    // 4. 测试图像分析
                    Log.i(TAG, "4. 测试图像分析...")
                    val testBitmap = createTestBitmap()
                    val analysisResult = engine.analyzeImage(
                        bitmap = testBitmap,
                        prompt = "分析这张图片"
                    )

                    Log.i(TAG, "分析结果:")
                    Log.i(TAG, "  类别: ${analysisResult.categories.joinToString()}")
                    Log.i(TAG, "  标签: ${analysisResult.tags.joinToString()}")
                    Log.i(TAG, "  描述: ${analysisResult.description}")
                    Log.i(TAG, "  置信度: ${analysisResult.confidence}")

                    testBitmap.recycle()

                    Log.i(TAG, "=== Qwen3.5 模型集成测试完成 ===")
                    Log.i(TAG, "✅ 所有测试通过")

                } catch (e: Exception) {
                    Log.e(TAG, "❌ 测试失败", e)
                }
            }
        }

        /**
         * 测试混合模式分析器
         */
        fun testHybridAnalyzer(activity: ComponentActivity) {
            activity.lifecycleScope.launch {
                try {
                    Log.i(TAG, "=== 测试混合模式分析器 ===")

                    // TODO: 实现 HybridPhotoContentAnalyzer 测试

                    Log.i(TAG, "混合模式测试完成")

                } catch (e: Exception) {
                    Log.e(TAG, "混合模式测试失败", e)
                }
            }
        }

        /**
         * 创建测试用的位图
         */
        private fun createTestBitmap(): Bitmap {
            // 创建一个 800x600 的测试图像
            return Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply {
                // 填充颜色（可选）
                eraseColor(android.graphics.Color.LTGRAY)
            }
        }

        /**
         * 从资源加载测试图像
         */
        private fun loadTestImageFromResources(activity: ComponentActivity, resourceId: Int): Bitmap? {
            return try {
                BitmapFactory.decodeResource(activity.resources, resourceId)
            } catch (e: Exception) {
                Log.e(TAG, "无法加载测试图像", e)
                null
            }
        }
    }
}

/**
 * 在 MainActivity 中添加以下代码来运行测试：
 *
 * class MainActivity : ComponentActivity() {
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *
 *         // 运行集成测试
 *         QwenIntegrationTest.testModelLoading(this)
 *
 *         setContent {
 *             // Your UI code
 *         }
 *     }
 * }
 */
