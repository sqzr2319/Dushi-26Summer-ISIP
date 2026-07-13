# Models Directory

This directory contains GGUF format models for on-device inference using llama.cpp.

## Required Model Files

### Gemma-4-E2B-it GGUF (Recommended)

**Files**:
1. `gemma-4-E2B_q4_0-it.gguf` - 主模型文件
   - **Size**: ~3.12 GB
   - **Type**: Vision-Language Multimodal Model
   - **Quantization**: Q4_0 (4-bit)
   
2. `gemma-4-E2B-it-mmproj.gguf` - 多模态投影层
   - **Size**: ~0.92 GB
   - **Required for**: Image analysis

**Total Size**: ~4 GB

**Capabilities**:
- Image classification
- OCR (text recognition)
- Tag generation
- Natural language description
- Privacy content detection

## How to Download the Model

### 使用 hf-mirror.com 镜像源下载

```bash
# 设置 Hugging Face 镜像源
export HF_ENDPOINT=https://hf-mirror.com

# 方法 1: 使用 huggingface-cli
pip install huggingface-hub
huggingface-cli download google/gemma-4-E2B-it-qat-q4_0-gguf \
  --local-dir ./models \
  --local-dir-use-symlinks False

# 方法 2: 使用 git clone（需要 git-lfs）
git lfs install
GIT_LFS_SKIP_SMUDGE=0 git clone https://hf-mirror.com/google/gemma-4-E2B-it-qat-q4_0-gguf ./models

# 方法 3: 直接下载（推荐用于单个文件）
wget https://hf-mirror.com/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B_q4_0-it.gguf
wget https://hf-mirror.com/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B-it-mmproj.gguf
```

### Windows PowerShell 下载

```powershell
# 设置镜像源
$env:HF_ENDPOINT = "https://hf-mirror.com"

# 使用 Invoke-WebRequest 下载
$baseUrl = "https://hf-mirror.com/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main"
Invoke-WebRequest -Uri "$baseUrl/gemma-4-E2B_q4_0-it.gguf" -OutFile "gemma-4-E2B_q4_0-it.gguf"
Invoke-WebRequest -Uri "$baseUrl/gemma-4-E2B-it-mmproj.gguf" -OutFile "gemma-4-E2B-it-mmproj.gguf"

# 或使用 huggingface-cli
pip install huggingface-hub
huggingface-cli download google/gemma-4-E2B-it-qat-q4_0-gguf `
  --local-dir models `
  --local-dir-use-symlinks False
```

## Placing the Model Files

下载完成后，将模型文件放置在此目录：

```
app/src/main/assets/models/
├── README.md                           (this file)
├── gemma-4-E2B_q4_0-it.gguf           (主模型, ~3.12 GB)
└── gemma-4-E2B-it-mmproj.gguf         (多模态投影层, ~0.92 GB)
```

**验证文件：**
```bash
cd app/src/main/assets/models/
ls -lh *.gguf

# 应该看到：
# -rw-r--r-- 1 user user 3.1G  gemma-4-E2B_q4_0-it.gguf
# -rw-r--r-- 1 user user 945M  gemma-4-E2B-it-mmproj.gguf
```

## Model Integration Status

### 当前状态（2026-07-13）

✅ **已完成：**
- GGUF 模型路径配置
- GemmaInferenceEngine 改写为支持 llama.cpp
- ModelConfig 更新为 Q4_0 量化
- 构建配置更新

⚠️ **待完成：**
- llama.cpp Android JNI 库集成
  - 选项 1: 使用 `de.kherud:llama:3.0.0`（如果可用）
  - 选项 2: 从源码编译 llama.cpp Android 库
  - 选项 3: 使用其他社区 wrapper
- 实际的模型加载和推理代码
- 多模态图像输入处理

### 集成 llama.cpp 的步骤

#### 选项 1：使用 Maven 依赖（最简单）

在 `app/build.gradle.kts` 中：
```kotlin
dependencies {
    // 尝试这些可用的 llama.cpp wrapper
    implementation("de.kherud:llama:3.0.0")
    // 或
    implementation("com.aallam.llama:llama-android:1.0.0")
}
```

#### 选项 2：从源码编译（最灵活）

```bash
# 克隆 llama.cpp
git clone https://github.com/ggerganov/llama.cpp
cd llama.cpp

# 编译 Android 库
mkdir build-android && cd build-android
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DLLAMA_CUBLAS=OFF

make -j$(nproc)

# 将生成的 .so 文件复制到项目
cp libllama.so ../app/src/main/jniLibs/arm64-v8a/
```

然后创建 JNI wrapper 或使用现有的 Android 绑定。

#### 选项 3：使用预编译库

查找社区提供的预编译 llama.cpp Android 库：
- https://github.com/gaborbata/llama.cpp-android
- https://github.com/JosefZelinka/llama-android

## 使用其他 GGUF 模型

如果 Gemma-4-E2B 不可用，可以使用这些替代模型：

### 仅文本模型（更小，无图像支持）
- **Gemma 2B IT** (~1.5 GB Q4_0)
- **Phi-3-mini** (~2.3 GB Q4_K_M)
- **Qwen2-1.5B** (~1 GB Q4_0)

### 多模态模型（支持图像）
- **LLaVA 1.5 7B** (~4 GB Q4_0)
- **MiniCPM-V 2.6** (~2 GB Q4_0)
- **Qwen2-VL-2B** (~1.5 GB Q4_0)

下载命令相同，只需替换模型仓库名称。

## 构建说明

### APK 大小影响
- 主模型: +3.12 GB
- 多模态投影层: +0.92 GB
- **总增加**: ~4 GB

**建议：**
1. 使用 Android App Bundle（AAB）而不是 APK
2. 或使用动态功能模块（Dynamic Feature Module）按需下载
3. 或在首次启动时从服务器下载模型到内部存储

### 首次构建
```bash
# 完整构建（包含模型）
./gradlew assembleDebug

# 构建时间会较长（需要打包 4GB 文件）
# APK 大小会达到 4+ GB
```

### 开发时排除模型
如果在开发时不想打包模型到 APK：

在 `build.gradle.kts` 中添加：
```kotlin
android {
    packagingOptions {
        resources {
            // 排除大型 GGUF 文件（开发用）
            excludes += "assets/models/*.gguf"
        }
    }
}
```

然后在运行时从外部存储或网络加载模型。

## Fallback Behavior

如果模型文件不存在：
1. 应用会尝试使用云端 API（Qwen3.5-VL）
2. 如果云端不可用，使用基于规则的基础分析
3. 应用不会崩溃 - 优雅降级

这样可以在开发阶段无模型工作。

## Model Verification

在代码中验证模型是否可用：

```kotlin
val modelManager = ModelManager.getInstance(context)

// 检查主模型
val mainModelAvailable = modelManager.isModelAvailable("gemma-4-E2B_q4_0-it.gguf")
Log.d("Model", "Main model available: $mainModelAvailable")

// 检查多模态投影层
val mmProjAvailable = modelManager.isModelAvailable("gemma-4-E2B-it-mmproj.gguf")
Log.d("Model", "MM projection available: $mmProjAvailable")

// 获取模型信息
val info = modelManager.getModelInfo("gemma-4-E2B_q4_0-it.gguf")
Log.d("Model", "Model size: ${info?.getReadableSize()}")
```

## 性能优化

### CPU vs GPU
- **CPU**: 默认启用，使用 4 线程
- **GPU**: 如果设备支持 Vulkan/OpenCL，可以启用加速

在 `ModelConfig` 中配置：
```kotlin
ModelConfig(
    useGPU = true,              // 启用 GPU（如果可用）
    numThreads = 4,             // CPU 线程数
    contextSize = 2048,         // 上下文大小
    temperature = 0.3f          // 采样温度
)
```

### 推理速度
- **首次加载**: 5-10 秒（加载 4GB 模型）
- **图像分析**: 3-8 秒（取决于设备性能）
- **纯文本**: 1-3 秒

### 内存需求
- **模型加载**: ~4 GB
- **推理时**: +1-2 GB
- **建议最低内存**: 6 GB RAM

## License

Gemma 4 模型遵循 Apache 2.0 许可证。

使用前请确认：
- https://ai.google.dev/gemma/terms
- https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf

## Troubleshooting

### 模型文件下载失败
```bash
# 检查网络连接
ping hf-mirror.com

# 尝试使用代理
export http_proxy=http://your-proxy:port
export https_proxy=http://your-proxy:port
```

### 模型加载失败
检查日志：
```bash
adb logcat | grep -E "(Gemma|llama|GGUF)"
```

常见问题：
- 文件路径错误
- 文件损坏（重新下载）
- 内存不足（设备 RAM < 6GB）
- llama.cpp 库未正确集成

### APK 构建失败
如果 APK 超过大小限制：
```kotlin
// 使用 splits 或 App Bundle
android {
    splits {
        abi {
            enable = true
            reset()
            include("arm64-v8a")
        }
    }
}
```

## Support

相关文档：
- llama.cpp: https://github.com/ggerganov/llama.cpp
- GGUF format: https://github.com/ggerganov/ggml/blob/master/docs/gguf.md
- Gemma models: https://ai.google.dev/gemma

遇到问题请检查：
1. logcat 日志
2. 模型文件完整性（MD5/SHA256）
3. llama.cpp 库是否正确集成
4. 设备性能是否满足要求
