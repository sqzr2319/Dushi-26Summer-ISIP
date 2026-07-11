package com.example.isip.domain.skill

/**
 * Agent 工具的统一调用协议。
 *
 * Skill 只负责业务编排，不负责加载具体模型。模型能力通过构造参数注入，
 * 从而使同一个 Skill 可在 Android 端和本机测试环境中复用。
 */
interface Skill<I, O> {
    suspend fun execute(input: I): O

    /** 提供给 Agent System Prompt 的工具说明。 */
    fun getToolDescription(): String
}
