## Skill 文件完整示例

---

### `AnalyzeImageSkill.kt` （李佳乔负责）

```kotlin
package com.example.photoagent.domain.skill

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * ============================================================
 * Skill 自然语言描述（给大模型看的工具说明）
 * 这部分会被杨祺瀚复制到 PromptManager.kt 的 System Prompt 中
 * ============================================================
 *
 * ## 工具名称
 * analyze_image
 *
 * ## 功能
 * 分析单张照片的内容，返回分类、OCR文字、标签和一句话描述
 *
 * ## 输入参数
 * - image_path (String): 图片文件路径
 *
 * ## 输出格式
 * {
 *   "image_id": "photo_001.jpg",
 *   "categories": ["人物", "家庭"],
 *   "ocr_text": "哈尔滨 2025.12",
 *   "tags": ["#旅行", "#家庭", "#冬天"],
 *   "description": "一家人在哈尔滨餐厅聚餐的照片",
 *   "confidence": 0.92
 * }
 *
 * ## 调用示例
 * {"tool": "analyze_image", "params": {"image_path": "/storage/emulated/0/DCIM/photo.jpg"}}
 *
 * ============================================================
 * 代码实现部分
 * ============================================================
 */

class AnalyzeImageSkill(
    private val inferEngine: InferenceEngine
) : Skill<AnalyzeImageSkill.Input, AnalyzeImageSkill.Output> {

    data class Input(
        val imagePath: String,
        val mode: AnalysisMode = AnalysisMode.COMPREHENSIVE
    )

    data class Output(
        val imageId: String,
        val categories: List<String>,
        val ocrText: String,
        val tags: List<String>,
        val description: String,
        val confidence: Float
    )

    enum class AnalysisMode {
        COMPREHENSIVE,
        CLASSIFICATION,
        OCR_ONLY
    }

    override suspend fun execute(input: Input): Output {
        // 1. 加载并压缩图片到模型输入尺寸
        val bitmap = loadAndResizeImage(input.imagePath)

        // 2. 根据模式构建 Prompt
        val prompt = buildPrompt(input.mode)

        // 3. 调用端侧大模型推理
        val rawResult = inferEngine.infer(
            prompt = prompt,
            image = bitmap,
            maxTokens = 256,
            temperature = 0.3f
        )

        // 4. 解析并返回结构化结果
        return parseResult(rawResult, input.imagePath)
    }

    private fun loadAndResizeImage(path: String): Bitmap {
        // TODO: 实现图片加载和压缩
        return BitmapFactory.decodeFile(path)
    }

    private fun buildPrompt(mode: AnalysisMode): String {
        return when (mode) {
            AnalysisMode.COMPREHENSIVE -> """
                分析这张照片，返回JSON格式：
                {"categories":["分类1","分类2"], "ocr":"图片中的文字", "tags":["#标签"], "description":"一句话描述"}
            """.trimIndent()
            AnalysisMode.CLASSIFICATION -> "判断照片类别，只返回一个词：人物/风景/美食/文档/截图/其他"
            AnalysisMode.OCR_ONLY -> "识别图片中所有文字，只返回文字内容"
        }
    }

    private fun parseResult(raw: String, imagePath: String): Output {
        // TODO: 解析大模型返回的JSON
        return Output(
            imageId = imagePath.substringAfterLast("/"),
            categories = listOf("人物", "家庭"),
            ocrText = "哈尔滨 2025.12",
            tags = listOf("#旅行", "#家庭", "#冬天"),
            description = "一家人在哈尔滨餐厅聚餐的照片",
            confidence = 0.92f
        )
    }

    override fun getToolDescription(): String = """
        |## 工具名称
        |analyze_image
        |
        |## 功能
        |分析单张照片的内容，返回分类、OCR文字、标签和一句话描述
        |
        |## 输入参数
        |- image_path (String): 图片文件路径
        |
        |## 输出格式
        |{
        |  "image_id": "photo_001.jpg",
        |  "categories": ["人物", "家庭"],
        |  "ocr_text": "哈尔滨 2025.12",
        |  "tags": ["#旅行", "#家庭", "#冬天"],
        |  "description": "一家人在哈尔滨餐厅聚餐的照片",
        |  "confidence": 0.92
        |}
        |
        |## 调用示例
        |{"tool": "analyze_image", "params": {"image_path": "/storage/emulated/0/DCIM/photo.jpg"}}
    """.trimMargin()
}
```

### `GenerateStrategySkill.kt` （李佳乔负责）

```kotlin
package com.example.photoagent.domain.skill

/**
 * ============================================================
 * Skill 自然语言描述
 * ============================================================
 *
 * ## 工具名称
 * generate_strategy
 *
 * ## 功能
 * 根据多张照片的分析结果，生成完整整理方案，包括事件聚合、
 * 重复照片检测和隐私风险提醒
 *
 * ## 输入参数
 * - analyses (List<ImageAnalysisResult>): 照片分析结果列表
 *
 * ## 输出格式
 * {
 *   "albums": [
 *     {"name": "哈尔滨冬日旅行", "photo_ids": ["img001", "img002"]}
 *   ],
 *   "duplicates": [
 *     {"photo_ids": ["img001", "img001_副本"], "similarity": 0.95}
 *   ],
 *   "privacy_risks": [
 *     {"image_id": "img005", "type": "身份证", "suggestion": "建议加密"}
 *   ]
 * }
 *
 * ============================================================
 * 代码实现部分
 * ============================================================
 */

class GenerateStrategySkill(
    private val inferEngine: InferenceEngine
) : Skill<GenerateStrategySkill.Input, GenerateStrategySkill.Output> {

    data class Input(
        val analyses: List<ImageAnalysisResult>
    )

    data class Output(
        val albums: List<EventAlbum>,
        val duplicates: List<DuplicateGroup>,
        val privacyRisks: List<PrivacyAlert>,
        val suggestions: List<String>
    )

    data class EventAlbum(
        val name: String,
        val eventDate: String?,
        val coverPhotoId: String,
        val photoIds: List<String>
    )

    data class DuplicateGroup(
        val photoIds: List<String>,
        val similarity: Float,
        val recommendKeep: String
    )

    data class PrivacyAlert(
        val imageId: String,
        val type: String,
        val description: String,
        val suggestion: String
    )

    override suspend fun execute(input: Input): Output {
        // 1. 构建包含所有分析结果的 Prompt
        val prompt = buildPrompt(input.analyses)

        // 2. 调用大模型生成整理方案
        val rawResult = inferEngine.infer(
            prompt = prompt,
            image = null,
            maxTokens = 512,
            temperature = 0.3f
        )

        // 3. 解析方案
        return parseResult(rawResult)
    }

    private fun buildPrompt(analyses: List<ImageAnalysisResult>): String {
        // 只取必要字段，减少 Token 消耗
        val summary = analyses.joinToString("\n") { analysis ->
            "- ${analysis.imageId}: ${analysis.categories} | ${analysis.ocrText} | ${analysis.tags}"
        }
        return """
            以下是 ${analyses.size} 张照片的分析结果：
            $summary

            请生成整理方案，按JSON格式返回：
            {
              "albums": [{"name": "相册名", "photo_ids": ["id1","id2"]}],
              "duplicates": [{"photo_ids": ["id1","id2"], "similarity": 0.95, "recommend_keep": "id1"}],
              "privacy_risks": [{"image_id": "id", "type": "身份证", "suggestion": "建议加密"}]
            }
        """.trimIndent()
    }

    private fun parseResult(raw: String): Output {
        // TODO: 解析JSON
        return Output(
            albums = emptyList(),
            duplicates = emptyList(),
            privacyRisks = emptyList(),
            suggestions = emptyList()
        )
    }

    override fun getToolDescription(): String = """
        |## 工具名称
        |generate_strategy
        |
        |## 功能
        |根据多张照片的分析结果，生成整理方案（事件聚合、重复检测、隐私提醒）
        |
        |## 输入参数
        |- analyses (List<ImageAnalysisResult>): 照片分析结果列表
        |
        |## 输出格式
        |{
        |  "albums": [{"name": "相册名", "photo_ids": ["id1","id2"]}],
        |  "duplicates": [{"photo_ids": ["id1","id2"], "similarity": 0.95}],
        |  "privacy_risks": [{"image_id": "id", "type": "身份证"}]
        |}
        |
        |## 调用示例
        |{"tool": "generate_strategy", "params": {"analyses": [...]}}
    """.trimMargin()
}
```

### `SearchPhotosSkill.kt` （孙长毅负责）

```kotlin
package com.example.photoagent.domain.skill

/**
 * ============================================================
 * Skill 自然语言描述
 * ============================================================
 *
 * ## 工具名称
 * search_photos
 *
 * ## 功能
 * 根据用户的自然语言描述，在已有照片分析结果中检索匹配的照片
 *
 * ## 输入参数
 * - query (String): 用户的搜索描述
 * - analyses (List<ImageAnalysisResult>): 已分析的照片列表
 *
 * ## 输出格式
 * {
 *   "query": "去年冬天在哈尔滨吃饭",
 *   "results": [
 *     {"image_id": "img001", "relevance_score": 0.92, "matched_tags": ["#旅行", "#冬天"]}
 *   ],
 *   "total_count": 12
 * }
 *
 * ============================================================
 * 代码实现部分
 * ============================================================
 */

class SearchPhotosSkill(
    private val inferEngine: InferenceEngine
) : Skill<SearchPhotosSkill.Input, SearchPhotosSkill.Output> {

    data class Input(
        val query: String,
        val analyses: List<ImageAnalysisResult>
    )

    data class Output(
        val query: String,
        val results: List<SearchItem>,
        val totalCount: Int
    )

    data class SearchItem(
        val imageId: String,
        val relevanceScore: Float,
        val matchedTags: List<String>,
        val matchedText: String
    )

    override suspend fun execute(input: Input): Output {
        // Step 1: 快速关键词匹配（粗筛）
        val candidates = keywordMatch(input.query, input.analyses)

        // Step 2: 大模型语义排序（精排）
        val ranked = semanticRank(input.query, candidates)

        return Output(
            query = input.query,
            results = ranked.take(20).map { candidate ->
                SearchItem(
                    imageId = candidate.imageId,
                    relevanceScore = candidate.score,
                    matchedTags = candidate.matchedTags,
                    matchedText = candidate.matchedText
                )
            },
            totalCount = ranked.size
        )
    }

    private fun keywordMatch(query: String, analyses: List<ImageAnalysisResult>): List<Candidate> {
        // TODO: 实现关键词过滤，返回候选列表
        return analyses.mapNotNull { analysis ->
            val score = computeKeywordScore(query, analysis)
            if (score > 0) Candidate(analysis.imageId, score, analysis.tags, analysis.ocrText) else null
        }
    }

    private fun computeKeywordScore(query: String, analysis: ImageAnalysisResult): Float {
        var score = 0f
        // 检查标签匹配
        analysis.tags.forEach { tag ->
            if (query.contains(tag.replace("#", ""))) score += 0.4f
        }
        // 检查OCR文本匹配
        if (query.any { analysis.ocrText.contains(it) }) score += 0.3f
        return score
    }

    private suspend fun semanticRank(query: String, candidates: List<Candidate>): List<Candidate> {
        if (candidates.isEmpty()) return emptyList()

        // TODO: 调用大模型进行语义排序
        val prompt = """
            用户想找：$query
            候选照片：${candidates.joinToString { it.imageId }}
            请选出最匹配的5张，按相关度排序，只返回照片ID列表。
        """.trimIndent()

        val result = inferEngine.infer(prompt = prompt, image = null)
        // 解析大模型返回的排序结果
        return candidates
    }

    data class Candidate(
        val imageId: String,
        val score: Float,
        val matchedTags: List<String>,
        val matchedText: String
    )

    override fun getToolDescription(): String = """
        |## 工具名称
        |search_photos
        |
        |## 功能
        |根据用户的自然语言描述检索匹配的照片
        |
        |## 输入参数
        |- query (String): 用户的搜索描述
        |- analyses (List<ImageAnalysisResult>): 已分析的照片列表
        |
        |## 输出格式
        |{
        |  "query": "去年冬天在哈尔滨吃饭",
        |  "results": [{"image_id": "img001", "relevance_score": 0.92}],
        |  "total_count": 12
        |}
        |
        |## 调用示例
        |{"tool": "search_photos", "params": {"query": "去年冬天在哈尔滨吃饭"}}
    """.trimMargin()
}
```

### `Skill.kt` + `SkillRegistry.kt` （杨祺瀚负责）

```kotlin
// ============================================================
// Skill.kt - Skill统一接口
// ============================================================

package com.example.photoagent.domain.skill

interface Skill<I, O> {
    suspend fun execute(input: I): O
    fun getToolDescription(): String
}


// ============================================================
// SkillRegistry.kt - Skill注册表
// ============================================================

package com.example.photoagent.domain.skill

class SkillRegistry(
    private val inferEngine: InferenceEngine
) {

    private val skills: Map<String, Skill<*, *>> = mapOf(
        "analyze_image" to AnalyzeImageSkill(inferEngine),
        "generate_strategy" to GenerateStrategySkill(inferEngine),
        "search_photos" to SearchPhotosSkill(inferEngine)
    )

    fun getSkill(toolName: String): Skill<*, *>? = skills[toolName]

    fun getAllDescriptions(): String =
        skills.values.joinToString("\n\n") { it.getToolDescription() }

    fun getToolNames(): List<String> = skills.keys.toList()

    suspend fun execute(toolName: String, params: Map<String, Any>): Any {
        val skill = getSkill(toolName) ?: throw IllegalArgumentException("Unknown tool: $toolName")
        return when (toolName) {
            "analyze_image" -> {
                val input = AnalyzeImageSkill.Input(
                    imagePath = params["image_path"] as String
                )
                (skill as AnalyzeImageSkill).execute(input)
            }
            "generate_strategy" -> {
                // TODO: 从params解析List<ImageAnalysisResult>
                val input = GenerateStrategySkill.Input(emptyList())
                (skill as GenerateStrategySkill).execute(input)
            }
            "search_photos" -> {
                val input = SearchPhotosSkill.Input(
                    query = params["query"] as String,
                    analyses = emptyList()  // TODO: 从数据库读取
                )
                (skill as SearchPhotosSkill).execute(input)
            }
            else -> throw IllegalArgumentException("Unknown tool: $toolName")
        }
    }
}
```

### `PromptManager.kt` （杨祺瀚负责）

```kotlin
package com.example.photoagent.ai

import com.example.photoagent.domain.skill.SkillRegistry

class PromptManager(
    private val skillRegistry: SkillRegistry
) {

    fun getSystemPrompt(): String = """
        你是一个个人相册整理助手。你的任务是理解用户的指令，
        调用合适的工具来完成相册管理操作。

        ${skillRegistry.getAllDescriptions()}

        请根据用户输入选择合适的工具，
        按以下JSON格式返回调用指令：
        {"tool": "工具名", "params": {"参数名": "参数值"}}
    """.trimIndent()
}
```

### 文件与负责人对照表

| 文件                         | 自然语言描述部分                            | 代码实现部分        | 负责人 |
|:-------------------------- |:----------------------------------- |:------------- |:--- |
| `AnalyzeImageSkill.kt`     | ✅ 写在文件顶部注释 + `getToolDescription()` | ✅ `execute()` | 李佳乔 |
| `GenerateStrategySkill.kt` | ✅ 写在文件顶部注释 + `getToolDescription()` | ✅ `execute()` | 李佳乔 |
| `SearchPhotosSkill.kt`     | ✅ 写在文件顶部注释 + `getToolDescription()` | ✅ `execute()` | 孙长毅 |
| `SkillRegistry.kt`         | ❌ 不含描述                              | ✅ 注册和调度       | 杨祺瀚 |
| `PromptManager.kt`         | ✅ 汇总所有描述到 System Prompt             | ✅ 管理Prompt    | 杨祺瀚 |

---

**总结**：自然语言描述和代码实现在**同一个文件**里，李佳乔和孙长毅在自己的 Skill 文件中写好描述，杨祺瀚通过 `SkillRegistry.getAllDescriptions()` 自动汇总到 System Prompt。这样维护简单，改一个文件就能同步更新描述和代码。
