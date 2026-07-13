package com.example.isip.data.ai

/**
 * Prompt模板管理器
 *
 * 管理各种分析任务的提示词模板
 */
object PromptManager {

    /**
     * 图像综合分析提示词
     */
    fun getComprehensiveAnalysisPrompt(): String = """
        请全面分析这张照片，并以JSON格式返回结果：

        {
          "categories": ["分类1", "分类2"],
          "tags": ["标签1", "标签2", "标签3", "标签4"],
          "ocr_text": "识别到的所有文字内容",
          "description": "用一句话描述这张照片的内容",
          "confidence": 0.85
        }

        分类建议：人物、美食、风景、宠物、文档、截图、建筑、出行、运动、其他
        标签要求：具体、相关、有用，3-6个
    """.trimIndent()

    /**
     * OCR专用提示词
     */
    fun getOcrPrompt(): String = """
        请识别图片中的所有文字内容，包括：
        - 中文文字
        - 英文文字
        - 数字
        - 标点符号

        按原文顺序返回识别结果。如果没有文字，返回空字符串。
    """.trimIndent()

    /**
     * 分类专用提示词
     */
    fun getClassificationPrompt(): String = """
        请判断这张照片属于以下哪个类别，只返回类别名称：

        - 人物：包含人物肖像、自拍、合影
        - 美食：食物、餐饮、烹饪
        - 风景：自然风光、城市景观
        - 宠物：猫、狗等动物
        - 文档：文件、票据、证件
        - 截图：手机或电脑截图
        - 建筑：房屋、建筑物、室内
        - 出行：交通工具、旅行
        - 运动：体育活动、健身
        - 其他：以上都不符合
    """.trimIndent()

    /**
     * 标签生成提示词
     */
    fun getTaggingPrompt(): String = """
        为这张照片生成3-6个相关标签，要求：
        1. 标签要具体、有用
        2. 优先描述主要内容
        3. 包含场景、物体、活动等信息
        4. 每个标签以#开头

        示例：#家庭聚会 #美食 #室内 #2024年春节
    """.trimIndent()

    /**
     * 截图专用分析提示词
     */
    fun getScreenshotAnalysisPrompt(): String = """
        这是一张手机或电脑截图，请分析：

        {
          "categories": ["截图"],
          "screenshot_type": "聊天/票据/文档/社交媒体/其他",
          "ocr_text": "截图中的所有文字",
          "tags": ["#截图", "#类型标签", "#其他相关标签"],
          "description": "简述截图内容",
          "contains_sensitive_info": false
        }

        注意识别是否包含敏感信息（身份证号、银行卡、密码等）
    """.trimIndent()

    /**
     * 隐私内容检测提示词
     */
    fun getPrivacyDetectionPrompt(): String = """
        检查这张照片是否包含以下隐私敏感信息：
        - 身份证
        - 护照
        - 银行卡
        - 社保卡
        - 驾驶证
        - 密码或验证码
        - 个人联系方式

        返回JSON格式：
        {
          "has_privacy_content": true/false,
          "privacy_type": "身份证/银行卡/...",
          "confidence": 0.9,
          "suggestion": "建议加密存储/建议删除"
        }
    """.trimIndent()

    /**
     * 重复照片检测提示词（用于语义理解）
     */
    fun getDuplicateDetectionPrompt(descriptions: List<String>): String = """
        以下是多张照片的描述：

        ${descriptions.mapIndexed { index, desc -> "${index + 1}. $desc" }.joinToString("\n")}

        请判断哪些照片可能是重复或相似的，返回JSON格式：
        {
          "duplicate_groups": [
            {"photo_indices": [1, 3, 5], "similarity_reason": "相同场景和内容"},
            {"photo_indices": [2, 4], "similarity_reason": "相似的人物和背景"}
          ]
        }
    """.trimIndent()

    /**
     * 照片整理策略生成提示词
     */
    fun getOrganizationStrategyPrompt(photoSummaries: List<String>): String = """
        以下是用户相册中的照片概况：

        ${photoSummaries.joinToString("\n")}

        请生成整理方案：
        1. 建议创建哪些相册（如"2024春节旅行"、"美食合集"等）
        2. 哪些照片应该归入每个相册
        3. 哪些照片可能是重复的
        4. 哪些照片包含隐私信息需要特别处理

        返回JSON格式：
        {
          "albums": [
            {
              "name": "相册名称",
              "description": "相册描述",
              "photo_ids": ["id1", "id2"]
            }
          ],
          "privacy_alerts": [
            {"photo_id": "id", "type": "身份证", "suggestion": "建议加密"}
          ]
        }
    """.trimIndent()

    /**
     * 搜索查询理解提示词
     */
    fun getSearchQueryPrompt(userQuery: String): String = """
        用户想搜索照片，查询内容是："$userQuery"

        请理解用户意图，提取关键信息：

        {
          "intent": "查找特定内容/查找时间段/查找地点",
          "keywords": ["关键词1", "关键词2"],
          "time_constraint": "2024年春节/去年夏天/上周/null",
          "location_constraint": "北京/海边/null",
          "category_filter": "人物/美食/风景/null"
        }
    """.trimIndent()

    /**
     * 构建System Prompt
     */
    fun getSystemPrompt(): String = """
        你是一个专业的照片分析助手，运行在用户的Android设备上。

        你的任务是：
        1. 准确识别照片内容和类型
        2. 提取照片中的文字信息（OCR）
        3. 生成有用的标签和描述
        4. 帮助用户整理和搜索照片
        5. 识别可能的隐私风险

        要求：
        - 返回结果必须是有效的JSON格式
        - 分析要准确、有用
        - 标签要具体、相关
        - 描述要简洁、清晰
        - 隐私检测要严格

        你的回答直接用于应用的数据处理，必须保证格式正确。
    """.trimIndent()

    /**
     * 根据分析模式获取提示词
     */
    fun getPromptForMode(mode: AnalysisMode): String = when (mode) {
        AnalysisMode.COMPREHENSIVE -> getComprehensiveAnalysisPrompt()
        AnalysisMode.CLASSIFICATION -> getClassificationPrompt()
        AnalysisMode.OCR_ONLY -> getOcrPrompt()
        AnalysisMode.TAGGING -> getTaggingPrompt()
        AnalysisMode.PRIVACY_CHECK -> getPrivacyDetectionPrompt()
        AnalysisMode.SCREENSHOT -> getScreenshotAnalysisPrompt()
    }
}

/**
 * 分析模式
 */
enum class AnalysisMode {
    COMPREHENSIVE,   // 综合分析
    CLASSIFICATION,  // 仅分类
    OCR_ONLY,       // 仅OCR
    TAGGING,        // 仅标签
    PRIVACY_CHECK,  // 隐私检测
    SCREENSHOT      // 截图专用
}
