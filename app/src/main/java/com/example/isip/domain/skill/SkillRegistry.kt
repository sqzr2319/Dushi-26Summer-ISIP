package com.example.photoagent.domain.skill

import com.example.photoagent.ai.InferenceEngine
import com.example.photoagent.data.model.ImageAnalysisResult
import com.example.photoagent.data.model.OrganizationPlan
import com.example.photoagent.data.model.SearchResult
import com.example.photoagent.utils.ToolCall

/**
 * Skill注册表
 * 管理所有可用的Skill，提供注册和调度功能
 */
class SkillRegistry(
    private val inferEngine: InferenceEngine
) {

    // Skill注册表：工具名 → Skill实例
    private val skills: MutableMap<String, Skill<*, *>> = mutableMapOf()

    init {
        // 自动注册所有Skill
        registerAll()
    }

    /**
     * 注册所有Skill
     */
    private fun registerAll() {
        register(AnalyzeImageSkill(inferEngine))
        register(GenerateStrategySkill(inferEngine))
        register(SearchPhotosSkill(inferEngine))
    }

    /**
     * 注册单个Skill
     */
    fun register(skill: Skill<*, *>) {
        skills[skill.getName()] = skill
    }

    /**
     * 获取所有Skill的文本描述
     */
    fun getAllDescriptions(): String {
        return skills.values.joinToString("\n\n") { it.getDescription() }
    }

    /**
     * 获取所有Skill名称列表
     */
    fun getAllNames(): List<String> = skills.keys.toList()

    /**
     * 根据工具名获取Skill
     */
    fun getSkillByName(name: String): Skill<*, *>? = skills[name]

    /**
     * 执行工具调用
     * @param toolCall 工具调用指令
     * @return 执行结果
     */
    suspend fun execute(toolCall: ToolCall): Any {
        val skill = getSkillByName(toolCall.tool)
            ?: throw IllegalArgumentException("未知工具: ${toolCall.tool}")

        return when (toolCall.tool) {
            "analyze_image" -> {
                val params = toolCall.params
                val imagePath = params["image_path"] as? String
                    ?: throw IllegalArgumentException("缺少参数: image_path")

                val input = AnalyzeImageSkill.Input(
                    imagePath = imagePath
                )
                (skill as AnalyzeImageSkill).execute(input)
            }

            "generate_strategy" -> {
                val params = toolCall.params
                val analyses = params["analyses"] as? List<*>
                    ?: throw IllegalArgumentException("缺少参数: analyses")

                // 类型转换（假设传入的是ImageAnalysisResult列表）
                @Suppress("UNCHECKED_CAST")
                val input = GenerateStrategySkill.Input(
                    analyses = analyses as List<ImageAnalysisResult>
                )
                (skill as GenerateStrategySkill).execute(input)
            }

            "search_photos" -> {
                val params = toolCall.params
                val query = params["query"] as? String
                    ?: throw IllegalArgumentException("缺少参数: query")

                val input = SearchPhotosSkill.Input(
                    query = query,
                    analyses = emptyList()  // TODO: 从数据库读取
                )
                (skill as SearchPhotosSkill).execute(input)
            }

            else -> throw IllegalArgumentException("不支持的工具: ${toolCall.tool}")
        }
    }

    /**
     * 检查工具是否存在
     */
    fun hasSkill(toolName: String): Boolean = skills.containsKey(toolName)

    /**
     * 获取Skill数量
     */
    fun getSkillCount(): Int = skills.size
}