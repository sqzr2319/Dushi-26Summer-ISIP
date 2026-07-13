# 个人相册整理智能体 - 开发计划文档

## 一、项目概述

### 1.1 项目背景
本项目旨在开发一个运行在端侧设备（Android）上的个人相册整理智能体，解决用户手机相册中照片、截图混乱，难以管理和检索的问题。

### 1.2 核心价值
- **本地化处理**：所有数据处理在端侧完成，保护用户隐私
- **智能化整理**：自动识别图片内容，生成分类和标签
- **高效检索**：支持模糊文本检索，快速找到目标照片
- **闭环体验**：完成"感知 → 理解 → 规划 → 执行 → 反馈"的完整流程

### 1.3 技术栈
- **开发语言**：Kotlin
- **开发环境**：Android Studio
- **AI核心**：多模态大模型（Qwen3.5-4B）
- **部署方案**：本地部署 + 云端API（可选）
- **推理框架**：ONNX Runtime / llama.cpp / MLC-LLM
- **硬件加速**：NPU/GPU
- **前端框架**：Android Jetpack Compose / XML

---

## 二、功能需求分析

### 2.1 图片内容理解模块
**功能描述**：使用多模态大模型（Qwen3.5-4B）对相册中的照片进行深度分析，提取图片信息

**技术方案**：
- **核心模型**：Qwen3.5-4B 多模态大模型
- **部署方式**：
  - **本地部署**：量化后的 Qwen3.5-4B-Instruct（INT4/INT8）
  - **云端API**：Qwen API（备用方案，处理复杂场景）
- **推理框架**：ONNX Runtime Mobile / llama.cpp / MLC-LLM

**具体功能**：
1. **图像理解（Vision）**
   - 识别照片类型：人物、风景、美食、文档、截图等
   - 场景识别：室内/室外、白天/夜晚等
   - 物体检测和识别
   
2. **文本识别（OCR）**
   - 通过多模态模型直接识别图像中的文字
   - 特别针对票据、聊天截图、文档照片
   - 支持中英文混合识别
   - 理解文字的语义和上下文
   
3. **智能标签生成**
   - 基于图像内容生成描述性标签
   - 提取关键元素（人物、物品、地点、活动等）
   - 生成自然语言描述和概述
   - 理解图片的情感和氛围

**输入**：图片文件路径或 Bitmap 对象 + Prompt 提示词  
**输出**：
```kotlin
data class ImageAnalysisResult(
    val imageId: String,
    val categories: List<String>,        // 分类标签
    val ocrText: String,                 // OCR识别文本
    val tags: List<String>,              // 关键标签
    val description: String,             // 文本概述
    val confidence: Float                // 置信度
)
```

### 2.2 整理策略生成模块
**功能描述**：基于图片分析结果，智能生成整理方案

**具体功能**：
1. **自动分类**
   - 按内容类型分类（人物、风景、截图、文档等）
   - 按时间分类（按年、月、日聚合）
   - 按事件分类（旅行、聚会、工作等）
   
2. **重复照片检测**
   - 识别相似或重复照片
   - 生成清理建议
   - 保留最佳质量版本
   
3. **隐私内容提醒**
   - 识别敏感信息（身份证、银行卡、密码等）
   - 标记隐私照片
   - 提供加密或删除建议

**输入**：图片分析结果列表  
**输出**：
```kotlin
data class OrganizationPlan(
    val categories: Map<String, List<String>>,  // 分类方案
    val duplicates: List<DuplicateGroup>,       // 重复照片组
    val privacyAlerts: List<PrivacyAlert>,      // 隐私提醒
    val suggestions: List<String>               // 整理建议
)

data class DuplicateGroup(
    val images: List<String>,      // 重复照片ID列表
    val similarity: Float,         // 相似度
    val recommendKeep: String      // 推荐保留的照片
)

data class PrivacyAlert(
    val imageId: String,
    val privacyType: String,       // 隐私类型
    val description: String,       // 描述
    val suggestion: String         // 建议操作
)
```

### 2.3 用户模糊检索模块
**功能描述**：支持用户通过自然语言描述检索相关照片

**技术方案**：
- 利用 Qwen3.5-4B 的语言理解能力
- 语义向量匹配（可选：使用轻量级 Embedding 模型）
- 多维度检索策略

**具体功能**：
1. **自然语言检索**
   - 支持复杂的自然语言查询（如"上周末拍的海边日落照片"）
   - 理解时间、地点、事件、人物等多维度信息
   - 支持模糊描述和同义词理解
   
2. **智能语义匹配**
   - 深度理解用户意图
   - 匹配图片描述、标签和OCR文本
   - 多模态匹配：文本+图像特征
   - 按相关度智能排序结果
   
3. **对话式检索**
   - 支持多轮对话式查询细化
   - 理解追问和筛选条件
   - 提供检索建议

**输入**：用户查询文本  
**输出**：
```kotlin
data class SearchResult(
    val query: String,
    val results: List<SearchItem>,
    val totalCount: Int
)

data class SearchItem(
    val imageId: String,
    val relevanceScore: Float,     // 相关度评分
    val matchedTags: List<String>, // 匹配的标签
    val matchedText: String        // 匹配的OCR文本
)
```

### 2.4 前端交互界面模块
**功能描述**：提供友好的用户交互界面

**具体功能**：
1. **输入交互**
   - 文本输入框
   - 语音输入支持
   
2. **照片展示**
   - 网格视图展示照片
   - 支持缩略图和详情查看
   - 分类标签展示
   
3. **整理结果呈现**
   - 可视化展示分类结果
   - 重复照片对比展示
   - 隐私提醒突出显示
   
4. **操作确认**
   - 批量删除确认
   - 整理方案预览
   - 操作撤销功能

### 2.5 端侧部署模块
**功能描述**：确保 Qwen3.5-4B 多模态大模型在端侧高效运行

**具体功能**：
1. **模型优化与部署**
   - **模型量化**：INT4/INT8 量化（4B 模型量化后约 2-3GB）
   - **格式转换**：转换为 ONNX / GGUF / MLC 格式
   - **模型切分**：按需加载模型层，减少内存占用
   - **KV Cache 优化**：优化推理内存占用
   
2. **推理框架选型**
   - **ONNX Runtime Mobile**：跨平台，支持量化
   - **llama.cpp (Android)**：高效的 LLM 推理，支持 GGUF 格式
   - **MLC-LLM**：专为移动端优化的 LLM 框架
   - **备选方案**：云端 API 调用（网络不稳定或设备性能不足时）
   
3. **硬件加速策略**
   - **GPU 加速**：优先使用 Vulkan/OpenCL 加速
   - **NPU 加速**：适配高通/MTK/麒麟 NPU（通过 NNAPI）
   - **CPU 优化**：NEON 指令集优化
   - **混合推理**：根据设备性能动态调整
   
4. **模块通信与调度**
   - 异步推理机制（Kotlin Coroutines）
   - 流式输出支持
   - 请求队列管理
   - 进度反馈和取消机制
   - 错误处理与降级策略（本地失败 → 云端API）
   
5. **性能优化**
   - Prompt 工程优化（减少 token 消耗）
   - 批量处理照片
   - 缓存机制（相同照片不重复分析）
   - 预热模型（首次推理优化）

---

## 三、技术架构设计

### 3.1 总体架构

```
┌─────────────────────────────────────────────────────┐
│                   前端 UI 层                         │
│  (Activity/Fragment + Compose/XML)                  │
│  - 照片网格展示 - 搜索界面 - 整理结果展示           │
└─────────────────┬───────────────────────────────────┘
                  │
┌─────────────────┴───────────────────────────────────┐
│                  业务逻辑层                          │
│  (ViewModel + Repository + UseCase)                 │
│  - 照片分析用例 - 整理策略用例 - 检索用例           │
└─────────────────┬───────────────────────────────────┘
                  │
┌─────────────────┴───────────────────────────────────┐
│              多模态大模型引擎层                      │
│  ┌──────────────────────────────────────────┐      │
│  │      Qwen3.5-4B 多模态大模型              │      │
│  │  (Vision + Language 统一处理)            │      │
│  │  - 图像理解  - OCR  - 标签生成  - 语义检索 │      │
│  └──────────────────────────────────────────┘      │
│  ┌──────────┐              ┌──────────┐            │
│  │ 本地推理  │              │ 云端API  │            │
│  │ (主要)   │ ←───降级───→ │ (备用)   │            │
│  └──────────┘              └──────────┘            │
└─────────────────┬───────────────────────────────────┘
                  │
┌─────────────────┴───────────────────────────────────┐
│                  推理框架层                          │
│  (ONNX Runtime Mobile / llama.cpp / MLC-LLM)       │
│  - Prompt 管理 - Token 处理 - 流式输出             │
└─────────────────┬───────────────────────────────────┘
                  │
┌─────────────────┴───────────────────────────────────┐
│                  硬件加速层                          │
│  GPU (Vulkan/OpenCL) / NPU (NNAPI) / CPU (NEON)   │
└─────────────────────────────────────────────────────┘
```

### 3.2 模块划分

#### 3.2.1 数据层（Data Layer）
- **PhotoRepository**：照片数据管理
- **CacheManager**：分析结果缓存
- **DatabaseHelper**：本地数据库（Room）

#### 3.2.2 AI 层（AI Layer）
- **QwenModelEngine**：Qwen3.5-4B 多模态大模型引擎（核心）
- **PromptManager**：Prompt 模板管理和优化
- **ModelLoader**：模型加载和初始化
- **InferenceManager**：推理请求调度和管理
- **CloudAPIClient**：云端 API 客户端（备用）
- **ResultParser**：模型输出解析器

#### 3.2.3 业务逻辑层（Domain Layer）
- **AnalysisUseCase**：照片分析用例
- **OrganizationUseCase**：整理策略用例
- **SearchUseCase**：检索用例

#### 3.2.4 表现层（Presentation Layer）
- **MainActivity**：主界面
- **PhotoGridFragment**：照片网格展示
- **SearchFragment**：搜索界面
- **OrganizationFragment**：整理结果展示

### 3.3 关键接口设计

```kotlin
// ========== 多模态大模型引擎接口 ==========

// Qwen 模型引擎主接口
interface QwenModelEngine {
    suspend fun initialize(modelPath: String, config: ModelConfig)
    suspend fun analyzeImage(
        imagePath: String, 
        prompt: String,
        mode: AnalysisMode = AnalysisMode.COMPREHENSIVE
    ): ImageAnalysisResult
    suspend fun searchImages(
        query: String,
        imageAnalyses: List<ImageAnalysisResult>
    ): SearchResult
    suspend fun chat(messages: List<ChatMessage>, imageUri: String? = null): String
    fun release()
}

// 模型配置
data class ModelConfig(
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val useGPU: Boolean = true,
    val useNPU: Boolean = true,
    val quantization: QuantizationType = QuantizationType.INT4,
    val fallbackToCloud: Boolean = true
)

enum class QuantizationType {
    INT4, INT8, FP16, FP32
}

enum class AnalysisMode {
    COMPREHENSIVE,  // 全面分析（分类+OCR+标签+描述）
    CLASSIFICATION, // 仅分类
    OCR_ONLY,       // 仅OCR
    TAGGING         // 仅标签生成
}

// Prompt 管理器
interface PromptManager {
    fun getAnalysisPrompt(mode: AnalysisMode): String
    fun getSearchPrompt(query: String): String
    fun getOrganizationPrompt(analyses: List<ImageAnalysisResult>): String
}

// 推理管理器
interface InferenceManager {
    suspend fun <T> infer(
        prompt: String,
        imageUri: String? = null,
        parser: (String) -> T
    ): T
    fun cancelInference()
    fun getInferenceStatus(): InferenceStatus
}

data class InferenceStatus(
    val isRunning: Boolean,
    val queueSize: Int,
    val currentTask: String?
)

// 云端 API 客户端
interface CloudAPIClient {
    suspend fun analyzeImage(imageBase64: String, prompt: String): String
    suspend fun chat(messages: List<ChatMessage>): String
    fun isAvailable(): Boolean
}

// 结果解析器
interface ResultParser {
    fun parseAnalysisResult(rawOutput: String): ImageAnalysisResult
    fun parseSearchResult(rawOutput: String, query: String): SearchResult
    fun parseOrganizationPlan(rawOutput: String): OrganizationPlan
}

// 聊天消息
data class ChatMessage(
    val role: String, // "system", "user", "assistant"
    val content: String,
    val imageUri: String? = null
)

// ========== 业务用例接口 ==========

// 照片分析用例
interface AnalysisUseCase {
    suspend fun analyzePhoto(photoId: String): ImageAnalysisResult
    suspend fun analyzeBatch(photoIds: List<String>): List<ImageAnalysisResult>
    suspend fun getAnalysisProgress(): Flow<AnalysisProgress>
}

data class AnalysisProgress(
    val total: Int,
    val completed: Int,
    val current: String?
)

// 整理策略用例
interface OrganizationUseCase {
    suspend fun generatePlan(analyses: List<ImageAnalysisResult>): OrganizationPlan
    suspend fun detectDuplicates(analyses: List<ImageAnalysisResult>): List<DuplicateGroup>
    suspend fun detectPrivacy(analysis: ImageAnalysisResult): PrivacyAlert?
}

// 检索用例
interface SearchUseCase {
    suspend fun search(query: String): SearchResult
    suspend fun conversationalSearch(conversation: List<String>): SearchResult
}
```

---

## 四、开发计划

### 第一阶段：基础框架搭建

**目标**：完成项目基础架构和基本界面

**任务清单**：
- [ ] 创建 Android 项目，配置 Gradle 依赖
- [ ] 搭建 MVVM 架构框架
- [ ] 设计并实现数据模型类
- [ ] 创建主界面和导航结构
- [ ] 实现照片读取和展示功能
- [ ] 配置 Room 数据库

**交付物**：
- 可运行的 Android 应用框架
- 基本的照片浏览功能

### 第二阶段：Qwen3.5-4B 模型集成

**目标**：集成 Qwen3.5-4B 多模态大模型，实现图片内容理解

**任务清单**：
- [ ] 调研推理框架（ONNX Runtime / llama.cpp / MLC-LLM）
- [ ] 获取并量化 Qwen3.5-4B 模型（INT4/INT8）
- [ ] 转换模型格式（ONNX / GGUF / MLC）
- [ ] 集成推理框架到 Android 项目
- [ ] 实现模型加载和初始化
- [ ] 设计并实现 Prompt 模板（图像分析、OCR、标签生成）
- [ ] 实现模型推理接口封装
- [ ] 配置硬件加速（GPU/NPU）
- [ ] 集成云端 API 作为备用方案
- [ ] 基础性能测试和调优

**交付物**：
- 可在 Android 端运行的 Qwen3.5-4B 模型
- 图像内容理解功能（分类+OCR+标签）
- 性能测试报告（推理速度、内存占用）

### 第三阶段：智能整理功能

**目标**：基于大模型能力实现整理策略生成

**任务清单**：
- [ ] 设计整理策略生成的 Prompt
- [ ] 实现照片自动分类逻辑（调用大模型）
- [ ] 实现重复照片检测算法（感知哈希 + 相似度计算）
- [ ] 实现隐私内容识别（通过大模型识别）
- [ ] 实现整理建议生成逻辑
- [ ] 设计并实现整理结果展示界面
- [ ] 实现批量操作功能

**交付物**：
- 完整的照片整理功能
- 整理结果展示界面

### 第四阶段：智能检索功能

**目标**：实现基于大模型的自然语言检索

**任务清单**：
- [ ] 设计语义检索的 Prompt 策略
- [ ] 实现自然语言查询理解（利用大模型）
- [ ] 实现多维度匹配算法（标签+OCR+语义）
- [ ] 实现搜索结果排序和过滤
- [ ] 支持对话式检索（多轮交互）
- [ ] 设计并实现搜索界面
- [ ] 添加语音输入支持

**交付物**：
- 完整的智能检索功能
- 搜索界面

### 第五阶段：端侧优化和完善

**目标**：优化大模型推理性能，完善用户体验

**任务清单**：
- [ ] 优化模型推理速度（KV Cache、Prompt 优化）
- [ ] 优化内存占用（模型分层加载、及时释放）
- [ ] 实现后台处理和进度显示
- [ ] 完善缓存机制（避免重复推理）
- [ ] 实现推理取消和超时机制
- [ ] 完善云端降级策略
- [ ] 完善错误处理和用户提示
- [ ] UI/UX 优化和流式输出展示

**交付物**：
- 性能优化报告
- 完善的用户界面
- 流畅的用户体验

**任务清单**：
- [ ] 接入硬件加速（GPU/NPU）
- [ ] 优化内存占用
- [ ] 实现后台处理和进度显示
- [ ] 添加缓存机制
- [ ] 完善错误处理
- [ ] UI/UX 优化

**交付物**：
- 性能优化报告
- 完善的用户界面

### 第六阶段：测试和 Demo 准备

**目标**：全面测试，准备展示 Demo

**任务清单**：
- [ ] 单元测试
- [ ] 集成测试
- [ ] 真机测试（多设备）
- [ ] Bug 修复
- [ ] 准备演示数据
- [ ] 录制 Demo 视频
- [ ] 编写使用文档

**交付物**：
- 测试报告
- Demo 视频
- 使用文档

---

## 五、技术难点与解决方案

### 5.1 Qwen3.5-4B 大模型端侧部署

**难点**：
- **模型体积大**：原始 4B 模型约 8GB（FP16），量化后仍需 2-3GB
- **推理速度慢**：大模型推理比传统视觉模型慢 10-100 倍
- **内存占用高**：运行时需要额外的 KV Cache 和中间层内存
- **设备性能差异**：旗舰机和中端机性能差距巨大
- **首次加载慢**：模型初始化和预热需要时间

**解决方案**：
1. **激进量化**：
   - 使用 INT4 量化（模型体积降至 2GB 左右）
   - 关键层使用 INT8，其他层 INT4 混合量化
   - 权重量化 + 激活值量化
   
2. **推理框架选型**：
   - **首选 llama.cpp**：支持 GGUF 格式，针对移动端优化，社区活跃
   - **备选 MLC-LLM**：Apache TVM 支持，编译优化
   - **备选 ONNX Runtime Mobile**：通用性好，量化支持完善
   
3. **分层加载策略**：
   - 核心层常驻内存，其他层按需加载
   - 使用 mmap 减少内存拷贝
   - 预热关键路径，加速首次推理
   
4. **硬件加速**：
   - GPU 优先（Vulkan/OpenCL），大幅提升矩阵运算速度
   - NPU 适配（高通 Hexagon、MTK APU、麒麟 NPU）
   - CPU 降级方案（NEON 指令集优化）
   
5. **云端降级**：
   - 检测设备性能，低端设备自动切换云端
   - 本地推理超时（>10s）自动降级
   - 网络正常时优先云端，离线时强制本地
   
6. **性能分级**：
   - **旗舰机**（8GB+ RAM）：本地运行 INT4 模型
   - **中端机**（6-8GB RAM）：本地运行 + 云端混合
   - **低端机**（<6GB RAM）：优先云端 API

### 5.2 大量照片处理性能

**难点**：
- 用户相册可能有数千张照片
- 大模型推理慢（单张 2-5 秒），全量分析不可接受
- 内存占用高，可能 OOM
- 用户等待时间过长，体验差

**解决方案**：
1. **智能批量处理**：
   - 首次使用：优先处理最近 100 张照片，后台逐步处理历史照片
   - 分批处理：每批 20-50 张，避免长时间阻塞
   - 可中断：支持暂停和恢复，不影响手机正常使用
   
2. **Prompt 优化减少推理时间**：
   - 精简 Prompt，减少输入 token 数
   - 限制输出长度（max_tokens=256），加速生成
   - 批量图片使用统一 System Prompt
   
3. **增量更新策略**：
   - 只分析新增和修改的照片
   - 已分析照片结果持久化到数据库
   - 相册变化监听，自动触发增量分析
   
4. **后台智能调度**：
   - 使用 WorkManager 在后台处理
   - 充电时优先处理（避免耗电）
   - WiFi 环境下可选云端加速
   - 手机空闲时提高处理优先级
   
5. **并发控制**：
   - 单线程推理（避免模型并发冲突）
   - 图片预处理并发（解码、压缩）
   - 请求队列管理，避免内存堆积
   
6. **内存优化**：
   - 图片压缩到模型所需分辨率（如 448x448）
   - 及时释放 Bitmap 和推理中间结果
   - 监控内存使用，接近阈值时暂停处理

### 5.3 检索准确性与响应速度

**难点**：
- 用户查询可能很模糊（"上个月的那张照片"）
- 自然语言理解对大模型推理能力要求高
- 每次搜索都调用大模型会很慢（2-5秒）
- 需要在准确性和速度之间平衡

**解决方案**：
1. **两阶段检索策略**：
   - **第一阶段**：快速粗筛（关键词匹配 + 时间过滤 + 标签匹配）< 100ms
   - **第二阶段**：精准排序（大模型语义理解）2-3s
   - 先展示粗筛结果，后台完成精准排序后更新
   
2. **利用预分析结果**：
   - 照片已有标签、描述、OCR 文本（分析阶段生成）
   - 搜索时直接匹配预分析结果，无需重新推理图片
   - 大模型只用于理解查询意图，不重复分析图片
   
3. **查询理解优化**：
   - 提取关键信息：时间、地点、人物、事件、物品
   - 简单查询直接匹配，复杂查询调用大模型
   - 缓存常见查询的理解结果
   
4. **语义向量匹配（可选）**：
   - 离线生成照片描述的 Embedding（使用轻量级模型）
   - 查询时生成 Embedding 并计算相似度
   - 速度快（<100ms），准确率中等
   
5. **用户反馈学习**：
   - 记录用户点击的搜索结果
   - 优化排序权重（标签权重、时间衰减等）
   - 逐步提升检索准确率

6. **对话式交互**：
   - 搜索结果不理想时，引导用户补充信息
   - 多轮对话缩小搜索范围
   - 利用上下文理解用户意图

### 5.4 隐私保护与安全

**难点**：
- 需要识别敏感信息（身份证、银行卡、隐私照片）
- 大模型可能"看到"用户所有照片内容
- 避免误判导致用户困扰
- 保证数据不外泄

**解决方案**：
1. **本地优先处理**：
   - 所有照片分析完全在本地完成
   - 模型不上传，数据不出设备
   - 分析结果加密存储在本地数据库
   
2. **云端 API 安全策略**：
   - 云端调用需要用户明确授权
   - 传输使用 HTTPS + 图片加密
   - 不存储用户照片（仅实时推理）
   - 敏感照片（身份证等）禁止上传云端
   
3. **敏感信息识别**：
   - **规则检测**：正则匹配身份证号、银行卡号、手机号
   - **大模型识别**：识别证件类型、隐私场景
   - **OCR + 分类结合**：先 OCR 提取文本，再判断类型
   
4. **用户控制权**：
   - 隐私提醒需用户确认，不自动操作
   - 用户可选择哪些照片参与分析
   - 支持关闭云端降级功能
   - 支持清除所有分析数据
   
5. **数据最小化**：
   - 只保存必要的分析结果（标签、描述）
   - 不保存原始图片缓存
   - 定期清理过期分析数据
   
6. **权限最小化**：
   - 只申请必需的系统权限（存储、相机）
   - 不申请位置、通讯录等无关权限
   - 明确告知用户每个权限的用途

---

## 六、测试计划

### 6.1 功能测试

| 测试项 | 测试内容 | 预期结果 |
|--------|----------|----------|
| 大模型加载 | 测试模型初始化和加载 | 加载成功，无崩溃 |
| 图像理解 | 测试各类照片分类和理解准确性 | 准确率 > 85% |
| OCR 识别 | 测试中英文文本识别（通过大模型） | 准确率 > 85% |
| 标签生成 | 测试标签相关性和质量 | 相关标签 ≥ 3 个 |
| 自然语言检索 | 测试复杂查询理解 | Top-10 准确率 > 75% |
| 对话式检索 | 测试多轮对话理解 | 上下文理解正确 |
| 重复检测 | 测试重复照片识别 | 召回率 > 90% |
| 隐私识别 | 测试敏感信息识别 | 识别率 > 80%，误报率 < 5% |
| 批量操作 | 测试批量删除等操作 | 功能正常无遗漏 |
| 云端降级 | 测试本地失败后云端降级 | 自动切换，无感知 |

### 6.2 性能测试

| 测试项 | 测试指标 | 目标值 |
|--------|----------|--------|
| 模型加载时间 | 冷启动加载模型 | < 5s（旗舰机）< 10s（中端机） |
| 单张照片分析 | 本地推理耗时 | < 3s（旗舰机）< 5s（中端机） |
| 云端 API 调用 | 网络请求 + 推理 | < 2s（正常网络） |
| 搜索响应时间 | 粗筛 + 精排 | 粗筛 < 200ms，精排 < 3s |
| 启动速度 | 冷启动时间（不加载模型） | < 2s |
| 运行时内存 | 模型加载 + 推理 | < 4GB（旗舰机）< 3GB（中端机） |
| 电量消耗 | 分析 100 张照片 | < 8% |
| 存储占用 | 模型 + 缓存 + 数据库 | 模型 < 2.5GB，总计 < 3GB |
| 批量处理速度 | 100 张照片处理时间 | < 10 分钟（后台） |

### 6.3 兼容性测试

**测试设备**：
- 高端设备：旗舰机型（如小米 14、华为 Mate 60）
- 中端设备：主流机型（如 Redmi Note 系列）
- 低端设备：入门机型

**测试系统**：
- Android 10、11、12、13、14

---

## 七、风险评估

| 风险 | 影响 | 概率 | 应对措施 |
|------|------|------|----------|
| 模型性能不足 | 高 | 中 | 准备多个候选模型，降级方案 |
| 端侧资源限制 | 高 | 高 | 充分优化，分级处理 |
| 开发时间不足 | 中 | 中 | 优先核心功能，砍掉次要功能 |
| 隐私识别误判 | 中 | 中 | 降低自动化程度，增加用户确认 |
| 兼容性问题 | 低 | 中 | 多设备测试，及时修复 |

---

## 八、项目交付标准

### 8.1 功能完整性
- ✅ 图像分类功能正常
- ✅ OCR 识别功能正常
- ✅ 自动整理功能正常
- ✅ 模糊检索功能正常
- ✅ 前端界面友好

### 8.2 性能指标
- ✅ 模型加载时间 < 10s（旗舰机）
- ✅ 单张照片分析 < 5s（本地）或 < 2s（云端）
- ✅ 应用运行时内存 < 4GB
- ✅ 支持 1000+ 照片处理

### 8.3 Demo 可展示性
- ✅ 清晰的输入展示
- ✅ 可视化的分析过程
- ✅ 完整的工具调用展示
- ✅ 明确的输出结果

### 8.4 文档完整性
- ✅ 技术设计文档
- ✅ API 接口文档
- ✅ 用户使用文档
- ✅ 测试报告

---

## 九、后续扩展方向

1. **增强 AI 能力**
   - 人脸识别和人物聚类
   - 场景深度理解
   - 照片质量评分

2. **云端协同**
   - 云端备份
   - 跨设备同步
   - 云端大模型辅助

3. **社交功能**
   - 照片分享
   - 相册协作
   - 智能推荐

4. **高级整理**
   - 自动创建相册
   - 智能视频剪辑
   - 回忆功能

---

## 十、参考资源

### 10.1 多模态大模型
- **Qwen3.5-4B-Instruct**：阿里通义千问多模态模型
  - 模型仓库：https://huggingface.co/Qwen/Qwen2.5-VL-4B-Instruct
  - 官方文档：https://qwenlm.github.io/
  - 量化工具：llama.cpp、GGUF 转换工具
  
- **备选模型**：
  - MiniCPM-V 2.6：面壁智能的端侧多模态模型（2B）
  - Phi-3.5-Vision：微软的轻量级视觉语言模型（3.8B）
  - InternVL2-4B：上海 AI Lab 的多模态模型

### 10.2 推理框架
- **llama.cpp**：https://github.com/ggerganov/llama.cpp
  - Android 移植：https://github.com/ggerganov/llama.cpp/tree/master/examples/llama.android
  - 支持 GGUF 格式，高效的移动端推理
  
- **MLC-LLM**：https://github.com/mlc-ai/mlc-llm
  - 基于 Apache TVM 的 LLM 编译器
  - 专为移动端优化，支持多种硬件加速
  
- **ONNX Runtime Mobile**：https://onnxruntime.ai/docs/tutorials/mobile/
  - 通用性好，支持量化和多平台
  - 需要模型转换为 ONNX 格式

### 10.3 模型量化工具
- **AutoGPTQ**：https://github.com/AutoGPTQ/AutoGPTQ
  - INT4/INT8 量化工具
  
- **llama.cpp 量化**：内置量化功能
  ```bash
  ./quantize model-f16.gguf model-q4_0.gguf q4_0
  ```
  
- **Optimum**：https://huggingface.co/docs/optimum/
  - Hugging Face 官方优化工具

### 10.4 Android 开发
- **Jetpack Compose**：https://developer.android.com/jetpack/compose
  - 现代化的 Android UI 框架
  
- **Kotlin Coroutines**：https://kotlinlang.org/docs/coroutines-overview.html
  - 异步编程支持
  
- **Room Database**：https://developer.android.com/training/data-storage/room
  - 本地数据库
  
- **WorkManager**：https://developer.android.com/topic/libraries/architecture/workmanager
  - 后台任务调度
  
- **CameraX**：https://developer.android.com/training/camerax
  - 相机和图片处理

### 10.5 云端 API
- **通义千问 API**：https://help.aliyun.com/zh/dashscope/
  - 阿里云百炼平台
  
- **OpenAI Vision API**：https://platform.openai.com/docs/guides/vision
  - GPT-4V 多模态能力
  
- **备选方案**：
  - Google Gemini API
  - Claude API（Anthropic）

### 10.6 算法参考
- **感知哈希算法**：用于重复照片检测
  - pHash（Perceptual Hash）
  - 实现库：https://github.com/JohannesBuchner/imagehash
  
- **图像相似度计算**：
  - 结构相似性（SSIM）
  - 特征点匹配（ORB、SIFT）

### 10.7 学习资源
- **Qwen 多模态使用教程**：
  - https://qwenlm.github.io/blog/qwen2-vl/
  
- **Android 端侧 AI 开发**：
  - https://developer.android.com/ai
  
- **大模型量化与部署**：
  - https://huggingface.co/docs/transformers/main/en/quantization

---

## 附录：项目结构示例

```
app/
├── src/
│   ├── main/
│   │   ├── java/com/example/photoagent/
│   │   │   ├── data/              # 数据层
│   │   │   │   ├── model/         # 数据模型
│   │   │   │   ├── repository/    # 数据仓库
│   │   │   │   └── database/      # 数据库
│   │   │   ├── domain/            # 业务逻辑层
│   │   │   │   ├── usecase/       # 用例
│   │   │   │   └── entity/        # 业务实体
│   │   │   ├── ai/                # AI 引擎层
│   │   │   │   ├── classifier/    # 图像分类
│   │   │   │   ├── ocr/          # OCR 引擎
│   │   │   │   ├── tagger/       # 标签生成
│   │   │   │   └── search/       # 语义搜索
│   │   │   ├── ui/                # 表现层
│   │   │   │   ├── main/         # 主界面
│   │   │   │   ├── gallery/      # 相册界面
│   │   │   │   ├── search/       # 搜索界面
│   │   │   │   └── organize/     # 整理界面
│   │   │   └── utils/             # 工具类
│   │   ├── assets/                # 模型文件
│   │   │   ├── classifier.tflite
│   │   │   └── ocr.tflite
│   │   └── res/                   # 资源文件
│   └── test/                      # 测试代码
└── build.gradle
```

---

**文档版本**：v1.0  
**创建日期**：2026-07-04  
**最后更新**：2026-07-04
