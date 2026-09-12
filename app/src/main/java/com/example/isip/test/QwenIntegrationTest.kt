package com.example.isip.test

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.example.isip.data.ai.AccelerationBackend
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

                    // 1. 检查模型文件（首次调用会把 assets 里的模型复制到私有目录）
                    //
                    // 用候选链而不是写死的 MODEL_FILE_NAME：后者指向 4B，设备上只部署
                    // 2B 时这个自检会误报"主模型不可用"。
                    Log.i(TAG, "1. 检查模型文件...")
                    val picked = QwenModel.ensurePreferredModel(activity)
                    val mainModelPath = picked?.second
                    val mmProjPath = QwenModel.ensureAvailable(activity, QwenModel.MMPROJ_FILE_NAME)

                    if (mainModelPath == null) {
                        Log.e(TAG, "❌ 主模型不可用，候选: ${QwenModel.MODEL_FILE_CANDIDATES}")
                        return@launch
                    }
                    if (mmProjPath == null) {
                        Log.e(TAG, "❌ 多模态投影层不可用: ${QwenModel.MMPROJ_FILE_NAME}")
                        return@launch
                    }
                    Log.i(TAG, "主模型: $mainModelPath")
                    Log.i(TAG, "投影层: $mmProjPath")

                    // 2. 初始化推理引擎
                    //
                    // 必须复用生产配置，不能自造一份。理由：QwenInferenceEngine 是
                    // 进程内单例，config 只在**首次创建**时生效 —— 如果这个自检入口先跑，
                    // 它会把单例锁在自己的配置上，之后生产路径传的 PRODUCTION_CONFIG
                    // 被静默忽略，表现为"输出莫名其妙不对"。
                    //
                    // 这里原先自造了一份带旧值的配置（quantizationType = Q2_K_XL、
                    // maxTokens = 256），正是上面那种污染源。
                    Log.i(TAG, "2. 初始化推理引擎...")
                    val engine = QwenInferenceEngine.getInstance(
                        activity,
                        com.example.isip.data.ai.HybridPhotoContentAnalyzer.PRODUCTION_CONFIG
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
