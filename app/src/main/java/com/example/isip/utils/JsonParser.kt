package com.example.photoagent.utils

import com.google.gson.Gson
import com.google.gson.JsonParser as GsonJsonParser
import com.google.gson.JsonSyntaxException

/**
 * JSON解析工具类
 */
object JsonParser {

    private val gson = Gson()

    /**
     * 解析大模型返回的工具调用指令
     * 期望格式: {"tool": "analyze_image", "params": {"image_path": "..."}}
     */
    fun parseToolCall(rawJson: String): ToolCall? {
        return try {
            val jsonObject = GsonJsonParser.parseString(rawJson).asJsonObject
            val tool = jsonObject.get("tool")?.asString ?: return null
            val paramsObject = jsonObject.getAsJsonObject("params") ?: return null

            val params = paramsObject.entrySet().associate { entry ->
                val value = entry.value
                entry.key to when {
                    value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString as Any
                    value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asNumber as Any
                    value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean as Any
                    value.isJsonArray -> value.asJsonArray.toString()
                    value.isJsonObject -> value.asJsonObject.toString()
                    else -> value.toString()
                }
            }

            ToolCall(tool, params)
        } catch (e: JsonSyntaxException) {
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 从大模型输出中提取JSON（处理markdown包裹等情况）
     */
    fun extractJson(rawOutput: String): String {
        // 尝试提取 ```json ... ``` 中的内容
        val jsonPattern = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val match = jsonPattern.find(rawOutput)
        if (match != null) {
            return match.groupValues[1].trim()
        }

        // 尝试提取 { ... } 内容
        val bracePattern = Regex("\\{[\\s\\S]*\\}")
        val braceMatch = bracePattern.find(rawOutput)
        if (braceMatch != null) {
            return braceMatch.value
        }

        return rawOutput.trim()
    }

    /**
     * 将对象转为JSON字符串
     */
    fun toJson(obj: Any): String {
        return gson.toJson(obj)
    }

    /**
     * 从JSON字符串解析对象
     */
    inline fun <reified T> fromJson(json: String): T? {
        return try {
            gson.fromJson(json, T::class.java)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 工具调用指令
 */
data class ToolCall(
    val tool: String,                      // 工具名称
    val params: Map<String, Any>           // 参数映射
)