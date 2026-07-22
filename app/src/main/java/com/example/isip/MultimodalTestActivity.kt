package com.example.isip

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.isip.data.ai.LlamaCppWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class MultimodalTestActivity : ComponentActivity() {

    private lateinit var tvOutput: TextView
    private lateinit var btnLoadModel: Button
    private lateinit var btnTestText: Button
    private lateinit var btnTestImage: Button
    private lateinit var btnSelectImage: Button
    
    private var llamaWrapper: LlamaCppWrapper? = null
    private var selectedImageBytes: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_multimodal_test)

        tvOutput = findViewById(R.id.tv_output)
        btnLoadModel = findViewById(R.id.btn_load_model)
        btnTestText = findViewById(R.id.btn_test_text)
        btnTestImage = findViewById(R.id.btn_test_image)
        btnSelectImage = findViewById(R.id.btn_select_image)

        // 请求存储权限
        requestPermissions()

        // 加载模型
        btnLoadModel.setOnClickListener {
            loadModel()
        }

        // 测试纯文本
        btnTestText.setOnClickListener {
            testTextPrompt()
        }

        // 选择图片
        btnSelectImage.setOnClickListener {
            selectImage()
        }

        // 测试多模态
        btnTestImage.setOnClickListener {
            testMultimodalPrompt()
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_IMAGES
        )
        val needRequest = permissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needRequest) {
            requestPermissionLauncher.launch(permissions)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private fun selectImage() {
        val intent = android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
        imagePickerLauncher.launch(intent)
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data
            uri?.let {
                val inputStream = contentResolver.openInputStream(it)
                // 解码为 Bitmap 并缩放到最大 640px，防止 OOM
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (bitmap != null) {
                    val maxSize = 640
                    val scale = minOf(
                        maxSize.toFloat() / bitmap.width,
                        maxSize.toFloat() / bitmap.height
                    )
                    val scaledBitmap = if (scale < 1.0f) {
                        Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * scale).toInt(),
                            (bitmap.height * scale).toInt(),
                            true
                        )
                    } else bitmap

                    val outputStream = ByteArrayOutputStream()
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                    selectedImageBytes = outputStream.toByteArray()

                    if (scaledBitmap != bitmap) scaledBitmap.recycle()
                    bitmap.recycle()

                    val sizeKb = selectedImageBytes?.size?.div(1024) ?: 0
                    Toast.makeText(this, "✅ 图片已选择: ${sizeKb} KB", Toast.LENGTH_SHORT).show()
                    tvOutput.append("\n✅ 图片已加载，大小: ${sizeKb} KB")
                }
            }
        }
    }

    private fun loadModel() {
        btnLoadModel.isEnabled = false
        tvOutput.append("\n📥 加载模型中...")

        lifecycleScope.launch {
            try {
                llamaWrapper = LlamaCppWrapper(applicationContext)
                val modelsDir = filesDir.resolve("models")
                val modelPath = modelsDir.resolve("Qwen3.5-4B-Q4_K_M.gguf").absolutePath
                val mmprojPath = modelsDir.resolve("mmproj-F16.gguf").absolutePath
                
                val success = withContext(Dispatchers.IO) {
                    llamaWrapper?.loadModel(modelPath, mmprojPath) ?: false
                }

                if (success) {
                    tvOutput.append("\n✅ 模型加载成功！")
                    Toast.makeText(this@MultimodalTestActivity, "✅ 模型加载成功", Toast.LENGTH_SHORT).show()
                } else {
                    val errMsg = llamaWrapper?.getLastError() ?: "未知错误"
                    tvOutput.append("\n❌ 模型加载失败: $errMsg")
                    Toast.makeText(this@MultimodalTestActivity, "❌ 加载失败", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                tvOutput.append("\n❌ 加载异常: ${e.message}")
                e.printStackTrace()
            }
            btnLoadModel.isEnabled = true
        }
    }

    private fun testTextPrompt() {
        val wrapper = llamaWrapper
        if (wrapper == null) {
            tvOutput.append("\n⚠️ 请先加载模型！")
            return
        }

        tvOutput.append("\n📤 发送纯文本测试...")
        
        lifecycleScope.launch {
            try {
                val prompt = "请用JSON输出：{\"tool\":\"search_photos\",\"arguments\":{\"keyword\":\"毕业\"}}"
                
                withContext(Dispatchers.IO) {
                    wrapper.generate(prompt, maxTokens = 256, temperature = 0.1f) { token ->
                        launch(Dispatchers.Main) {
                            tvOutput.append(token)
                        }
                    }
                }
                tvOutput.append("\n✅ 纯文本测试完成！")
            } catch (e: Exception) {
                tvOutput.append("\n❌ 错误: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun testMultimodalPrompt() {
        val wrapper = llamaWrapper
        if (wrapper == null) {
            tvOutput.append("\n⚠️ 请先加载模型！")
            return
        }

        val imageBytes = selectedImageBytes
        if (imageBytes == null) {
            tvOutput.append("\n⚠️ 请先选择一张图片！")
            Toast.makeText(this, "请先点击「选择图片」", Toast.LENGTH_SHORT).show()
            return
        }

        tvOutput.append("\n📤 发送多模态分析请求...")
        
        lifecycleScope.launch {
            try {
                val prompt = "请描述这张图片的内容，用JSON格式输出：场景、主要物体、标签"
                
                withContext(Dispatchers.IO) {
                    wrapper.generateMultimodal(imageBytes, prompt, maxTokens = 256, temperature = 0.1f) { token ->
                        launch(Dispatchers.Main) {
                            tvOutput.append(token)
                        }
                    }
                }
                tvOutput.append("\n✅ 多模态测试完成！")
            } catch (e: Exception) {
                tvOutput.append("\n❌ 错误: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}
