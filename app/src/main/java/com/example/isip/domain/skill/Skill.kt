package com.example.photoagent.domain.skill

/**
 * Skill统一接口
 * 所有技能模块必须实现此接口
 */
interface Skill<I, O> {

    /**
     * 执行Skill
     * @param input 输入参数
     * @return 执行结果
     */
    suspend fun execute(input: I): O

    /**
     * 获取Skill的自然语言描述（用于System Prompt）
     */
    fun getDescription(): String

    /**
     * 获取Skill名称
     */
    fun getName(): String
}