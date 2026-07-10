package com.example.photoagent.domain.agent

import com.example.photoagent.ai.InferenceEngine
import com.example.photoagent.ai.PromptManager
import com.example.photoagent.data.model.AgentResponse
import com.example.photoagent.data.model.UserInput
import com.example.photoagent.domain.skill.SkillRegistry
import com.example.photoagent.utils.JsonParser

/**
 * 智能体调度引擎
 * 核心调度中枢，负责意图理解、任务规划和Skill调度
 */
class PhotoAgentEngine(
    private val inferEngine: InferenceEngine,
    private val skillRegistry: SkillRegistry,
    private val promptManager: PromptManager
) {

    // 对话历史
    private val conversationHistory = mutableListOf<Pair<String, String>>()

    /**
     * 处理用户输入
     * 这是整个系统的入口方法
     */
    suspend fun processInput(userInput: UserInput): AgentResponse {
        return try {
            // 1. 获取用户文本
            val query = userInput.text

            // 2. 构建完整Prompt（System + 用户输入 + 历史）
            val systemPrompt = promptManager.getSystemPrompt()
            val historyText = buildHistoryText()
            val fullPrompt = """
                $systemPrompt

                对话历史：
                $historyText

                用户输入：$query

                请分析用户意图，返回工具调用指令。
                如果不需要调用工具，直接回复用户。
            """.trimIndent()

            // 3. 调用大模型推理
            val rawOutput = inferEngine.inferText(
                prompt = fullPrompt,
                maxTokens = 256,
                temperature = 0.3f
            )

            // 4. 解析大模型输出
            val jsonStr = JsonParser.extractJson(rawOutput)
            val toolCall = JsonParser.parseToolCall(jsonStr)

            // 5. 执行工具调用 或 直接回复
            val result = if (toolCall != null && skillRegistry.hasSkill(toolCall.tool)) {
                // 调用Skill
                val skillResult = skillRegistry.execute(toolCall)
                // 记录历史
                conversationHistory.add(query to "调用了 ${toolCall.tool}")
                AgentResponse(
                    success = true,
                    message = "已执行 ${toolCall.tool}",
                    data = skillResult,
                    toolCalls = listOf(toolCall)
                )
            } else {
                // 大模型直接回复（不需要调用工具）
                conversationHistory.add(query to rawOutput)
                AgentResponse(
                    success = true,
                    message = rawOutput,
                    data = null
                )
            }

            // 6. 限制历史长度
            if (conversationHistory.size > 20) {
                conversationHistory.removeAt(0)
            }

            result

        } catch (e: Exception) {
            AgentResponse(
                success = false,
                message = "处理失败: ${e.message}",
                data = null,
                error = e
            )
        }
    }

    /**
     * 处理语音输入（转文本后调用）
     */
    suspend fun processVoiceInput(audioData: ByteArray): AgentResponse {
        // TODO: 语音转文字
        val text = convertSpeechToText(audioData)
        return processInput(UserInput(text, UserInput.InputType.VOICE))
    }

    /**
     * 构建对话历史文本
     */
    private fun buildHistoryText(): String {
        if (conversationHistory.isEmpty()) return "无"

        return conversationHistory.takeLast(6).joinToString("\n") { (user, assistant) ->
            "用户: $user\n助手: $assistant"
        }
    }

    /**
     * 语音转文字（TODO: 实现）
     */
    private fun convertSpeechToText(audioData: ByteArray): String {
        // 暂返回空字符串
        return ""
    }

    /**
     * 重置对话
     */
    fun resetConversation() {
        conversationHistory.clear()
    }

    /**
     * 获取对话历史
     */
    fun getHistory(): List<Pair<String, String>> = conversationHistory.toList()
}

/**
 * 用户输入
 */
data class UserInput(
    val text: String,
    val type: InputType = InputType.TEXT
) {
    enum class InputType {
        TEXT, VOICE
    }
}

/**
 * 智能体响应
 */
data class AgentResponse(
    val success: Boolean,
    val message: String,
    val data: Any? = null,
    val toolCalls: List<Any>? = null,
    val error: Throwable? = null
)