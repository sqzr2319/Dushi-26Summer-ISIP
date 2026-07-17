package com.example.isip.domain.skill

/**
 * 为一张或多张照片添加用户标签。
 *
 * 标签存储由 [TagStore] 注入；Skill 只做参数校验、规范化和调用编排，因此不会让
 * UI 直接依赖 Room。实现应保留模型生成的标签，不能用本次手动标签覆盖它们。
 */
class AddTagSkill(
    private val tagStore: TagStore
) : Skill<AddTagSkill.Input, AddTagSkill.Output> {

    data class Input(
        val photoIds: List<String>,
        val tags: List<String>,
        val source: String = "user"
    )

    data class Output(
        val updatedPhotoIds: List<String>,
        val tags: List<String>,
        val skippedPhotoIds: List<String>
    )

    fun interface TagStore {
        /** 返回实际存在且已写入标签的 photoId。 */
        suspend fun addTags(photoIds: List<String>, tags: List<String>, source: String): List<String>
    }

    override suspend fun execute(input: Input): Output {
        val photoIds = input.photoIds.map(String::trim).filter(String::isNotEmpty).distinct()
        require(photoIds.isNotEmpty()) { "至少选择一张照片" }

        val tags = input.tags.map(::canonicalTag).filterNotNull().distinctBy(::normalizedTag)
        require(tags.isNotEmpty()) { "至少提供一个有效标签" }
        require(tags.size <= MAX_TAGS_PER_OPERATION) {
            "一次最多添加 $MAX_TAGS_PER_OPERATION 个标签"
        }

        val updated = tagStore.addTags(photoIds, tags, input.source.trim().ifBlank { "user" })
            .distinct()
        return Output(
            updatedPhotoIds = updated,
            tags = tags,
            skippedPhotoIds = photoIds - updated.toSet()
        )
    }

    private fun canonicalTag(raw: String): String? {
        val value = raw.trim().removePrefix("#").replace(WHITESPACE, " ")
        if (value.isEmpty()) return null
        require(value.length <= MAX_TAG_LENGTH) { "标签不能超过 $MAX_TAG_LENGTH 个字符" }
        return "#$value"
    }

    private fun normalizedTag(tag: String): String = tag.removePrefix("#").lowercase()

    override fun getToolDescription(): String = """
        |## 工具名称
        |add_tag
        |
        |## 功能
        |向一张或多张本地照片添加用户标签。标签会与 AI 标签合并保存；重新分析照片也不会删除用户标签。
        |
        |## 输入参数
        |- photo_ids (Array<String>, 必填): 待标记的本地照片 ID
        |- tags (Array<String>, 必填): 标签文本，可带或不带 #
        |
        |## 输出
        |updatedPhotoIds、tags、skippedPhotoIds。
    """.trimMargin()

    private companion object {
        const val MAX_TAGS_PER_OPERATION = 10
        const val MAX_TAG_LENGTH = 48
        val WHITESPACE = Regex("\\s+")
    }
}
