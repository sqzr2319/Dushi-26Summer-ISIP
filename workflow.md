

## **完整调用流程**

---

### **第 0 步：App 启动初始化**

**文件**：`MainActivity.kt` —— **褚一枫** 编写  
**作用**：加载模型、注册所有 Skill、初始化调度引擎。

```kotlin
// MainActivity.kt
class MainActivity {
    private lateinit var agentEngine: PhotoAgentEngine
    private lateinit var promptManager: PromptManager

    override fun onCreate() {
        // 1. 加载端侧模型（杨祺瀚的模块）
        val inferEngine = InferenceEngineImpl()
        inferEngine.loadModel("qwen-4b-q4.gguf")   // 调用 ModelLoader

        // 2. 创建 Skill 注册表（注册李佳乔+孙长毅的 Skill）
        val skillRegistry = SkillRegistry(inferEngine)

        // 3. 创建 Prompt 管理器，汇总所有 Skill 描述
        promptManager = PromptManager(skillRegistry)

        // 4. 创建智能体调度引擎
        agentEngine = PhotoAgentEngine(inferEngine, skillRegistry, promptManager)
    }
}
```

**此时 `PromptManager` 生成的系统 Prompt 内容**（由 **杨祺瀚** 的 `PromptManager.kt` 动态生成）：

```text
你是一个个人相册整理助手。你有以下工具可用：
- analyze_image：分析图片内容，返回分类、标签、OCR 文字。
- generate_strategy：根据多张照片生成整理方案（按日期/场景/人物分组）。
- search_photos：根据用户自然语言描述检索照片。

请根据用户输入选择合适的工具，按 JSON 格式返回调用指令。
```

---

### **第 1 步：用户输入 → 前端传给调度引擎**

**文件**：`PhotoGridFragment.kt` —— **褚一枫** 编写  
**触发**：用户在搜索框输入“帮我整理相册”并点击按钮。

```kotlin
// PhotoGridFragment.kt
fun onUserQuery(text: String) {
    // 调用杨祺瀚的调度引擎
    val response = agentEngine.processInput(text)   // 进入调度层
    // 展示结果（UI 更新）
    showResult(response)
}
```

---

### **第 2 步：调度引擎 → 大模型解析意图 → 返回工具调用指令**

**文件**：`PhotoAgentEngine.kt` —— **杨祺瀚** 编写  
**作用**：组装 Prompt，调用大模型推理，解析出要调用的 Skill 名称和参数。

```kotlin
// PhotoAgentEngine.kt
suspend fun processInput(userInput: String): AgentResponse {

    // 2.1 获取系统 Prompt（包含所有 Skill 描述）
    val systemPrompt = promptManager.getSystemPrompt()

    // 2.2 构建完整 Prompt = 系统指令 + 用户输入
    val fullPrompt = """
        $systemPrompt

        用户输入：$userInput
        请返回工具调用指令（JSON 格式）。
    """.trimIndent()

    // 2.3 调用端侧大模型推理（杨祺瀚的 InferenceEngineImpl）
    val rawOutput = inferEngine.infer(
        prompt = fullPrompt,
        maxTokens = 256,
        temperature = 0.3f
    )
    // 大模型返回示例：{"tool": "analyze_image", "params": {"image_path": "photo_001.jpg"}}

    // 2.4 解析 JSON，提取工具名和参数
    val toolCall = JsonParser.parse(rawOutput)

    // 2.5 通过 SkillRegistry 执行对应的 Skill
    val result = skillRegistry.execute(
        toolName = toolCall.tool,      // "analyze_image"
        params = toolCall.params       // {"image_path": "photo_001.jpg"}
    )

    return AgentResponse(success = true, data = result)
}
```

---

### **第 3 步：SkillRegistry 路由 → 找到并执行具体 Skill**

**文件**：`SkillRegistry.kt` —— **杨祺瀚** 编写  
**作用**：维护工具名 → Skill 对象的映射表，根据大模型返回的工具名分发执行。

```kotlin
// SkillRegistry.kt
class SkillRegistry(private val inferEngine: InferenceEngine) {

    // 注册表：工具名 → Skill 实例
    private val skills = mapOf(
        "analyze_image" to AnalyzeImageSkill(inferEngine),      // 李佳乔写
        "generate_strategy" to GenerateStrategySkill(inferEngine), // 李佳乔写
        "search_photos" to SearchPhotosSkill(inferEngine)       // 孙长毅写
    )

    suspend fun execute(toolName: String, params: Map<String, Any>): Any {
        return when (toolName) {
            "analyze_image" -> {
                val input = AnalyzeImageSkill.Input(
                    imagePath = params["image_path"] as String
                )
                // 调用李佳乔的 Skill
                (skills["analyze_image"] as AnalyzeImageSkill).execute(input)
            }
            "generate_strategy" -> {
                // 调用李佳乔的整理策略 Skill
                val input = GenerateStrategySkill.Input(
                    photoPaths = params["photo_paths"] as List<String>
                )
                (skills["generate_strategy"] as GenerateStrategySkill).execute(input)
            }
            "search_photos" -> {
                // 调用孙长毅的检索 Skill
                val input = SearchPhotosSkill.Input(
                    query = params["query"] as String
                )
                (skills["search_photos"] as SearchPhotosSkill).execute(input)
            }
            else -> throw IllegalArgumentException("未知工具: $toolName")
        }
    }
}
```

---

### **第 4 步：具体 Skill 执行 — 以 `AnalyzeImageSkill` 为例**

**文件**：`AnalyzeImageSkill.kt` —— **李佳乔** 编写  
**作用**：真正执行图片分析任务（分类、OCR、标签生成）。

```kotlin
// AnalyzeImageSkill.kt
class AnalyzeImageSkill(
    private val inferEngine: InferenceEngine
) {

    suspend fun execute(input: Input): Output {
        // 4.1 加载并压缩图片（节省显存）
        val bitmap = loadAndResizeImage(input.imagePath)

        // 4.2 构建图片分析 Prompt
        val prompt = """
            分析这张照片，返回 JSON 格式：
            {
              "categories": ["分类1", "分类2"],
              "ocr": "图片中的文字",
              "tags": ["#标签1", "#标签2"],
              "description": "一句话描述"
            }
        """

        // 4.3 再次调用大模型（图片理解），传入图片
        val rawResult = inferEngine.infer(
            prompt = prompt,
            image = bitmap,          // 多模态输入
            maxTokens = 256
        )
        // 大模型返回示例：
        // {"categories":["人物","家庭"], "ocr":"哈尔滨 2025.12", "tags":["#旅行","#家庭"], "description":"家庭合影"}

        // 4.4 解析 JSON 并封装为 Output
        return parseResult(rawResult, input.imagePath)
    }
}
```

**同样逻辑**：

- **`GenerateStrategySkill.kt`**（李佳乔）：分析多张照片，生成整理方案（按日期/场景/人物分组）。
- **`SearchPhotosSkill.kt`**（孙长毅）：将用户自然语言转为向量/关键词，检索本地照片。

---

### **第 5 步：结果逐层返回 → 前端展示**

```text
AnalyzeImageSkill.execute() 返回 Output
        ↓
SkillRegistry.execute() 返回 Output
        ↓
PhotoAgentEngine.processInput() 返回 AgentResponse
        ↓
PhotoGridFragment.showResult() 更新 UI（褚一枫）
```

---

## **完整调用链**

```text
[用户] 输入 "帮我整理相册"
    ↓
📱 前端层（褚一枫）: PhotoGridFragment.onUserQuery()
    ↓
🧠 调度层（杨祺瀚）: PhotoAgentEngine.processInput()
    ├── 调用 InferenceEngine.infer() → 大模型返回 {"tool":"analyze_image", ...}
    ├── 解析 JSON
    └── 调用 SkillRegistry.execute("analyze_image", params)
            ↓
📦 注册表（杨祺瀚）: SkillRegistry.execute()
    └── 从 skills 字典找到 AnalyzeImageSkill
            ↓
⚙️ 执行层（李佳乔）: AnalyzeImageSkill.execute()
    ├── 加载图片
    ├── 再次调用 InferenceEngine.infer()（多模态）
    └── 返回 Output {分类, OCR, 标签, 描述}
            ↓
🔄 结果逐层返回至 PhotoGridFragment
    ↓
📱 前端展示整理结果（褚一枫）
```

---

## **各文件调用时机与负责人总览**

| 步骤            | 被调用的文件                     | 负责人 | 作用                        |
|:------------- |:-------------------------- |:--- |:------------------------- |
| **App 初始化**   | `MainActivity.kt`          | 褚一枫 | 初始化调度引擎、注册 Skill、加载模型     |
| **用户输入**      | `PhotoGridFragment.kt`     | 褚一枫 | 接收用户查询，调用调度引擎             |
| **意图理解**      | `PhotoAgentEngine.kt`      | 杨祺瀚 | 组装 Prompt，调用大模型解析工具调用指令   |
| **工具路由**      | `SkillRegistry.kt`         | 杨祺瀚 | 根据工具名找到对应的 Skill 实例并执行    |
| **图片分析**      | `AnalyzeImageSkill.kt`     | 李佳乔 | 分析单张图片（分类、OCR、标签）         |
| **整理策略**      | `GenerateStrategySkill.kt` | 李佳乔 | 生成多张照片的整理方案（分组）           |
| **模糊检索**      | `SearchPhotosSkill.kt`     | 孙长毅 | 自然语言检索照片                  |
| **模型推理**      | `InferenceEngineImpl.kt`   | 杨祺瀚 | 所有大模型调用的底层接口（文本+多模态）      |
| **Prompt 管理** | `PromptManager.kt`         | 杨祺瀚 | 汇总所有 Skill 描述，生成系统 Prompt |
| **数据模型**      | `ImageAnalysisResult.kt` 等 | 全体  | 各模块间的数据传输对象（DTO）          |

---

## **核心要点**

1. **调度引擎（杨祺瀚的 `PhotoAgentEngine`）** 是大脑，负责让大模型决定“该调用哪个 Skill”。
2. **`SkillRegistry`** 是路由表，根据大模型的输出将请求分发到对应的 Skill。
3. **具体的 Skill（李佳乔和孙长毅写的）** 是手脚，真正干活（分析图片、生成策略、检索）。
4. **所有大模型推理** 都通过 **`InferenceEngineImpl`**（杨祺瀚）统一调用，保证底层一致性。
5. **数据在各层之间按接口定义流动**，解耦清晰，便于并行开发和测试。
