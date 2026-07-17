package com.example.isip.domain.skill

import com.example.isip.data.model.SmartAlbum
import com.example.isip.data.model.SmartAlbumRule

/** 创建并持久化一个可随新增照片重新计算的智能相册规则。 */
class CreateSmartAlbumSkill(
    private val albumStore: AlbumStore
) : Skill<CreateSmartAlbumSkill.Input, SmartAlbum> {

    data class Input(
        val name: String,
        val photoIds: List<String> = emptyList(),
        val categories: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val matchAllTags: Boolean = false,
        val createdBy: String = "user"
    )

    fun interface AlbumStore {
        /** 存储并返回包含数据库 ID 的相册。 */
        suspend fun save(album: SmartAlbum): SmartAlbum
    }

    override suspend fun execute(input: Input): SmartAlbum {
        val name = input.name.trim().replace(WHITESPACE, " ")
        require(name.isNotEmpty()) { "智能相册名称不能为空" }
        require(name.length <= MAX_NAME_LENGTH) { "智能相册名称不能超过 $MAX_NAME_LENGTH 个字符" }

        val photoIds = input.photoIds.map(String::trim).filter(String::isNotEmpty).distinct()
        val categories = input.categories.map(::cleanTerm).filterNotNull().distinct()
        val tags = input.tags.map(::cleanTag).filterNotNull().distinctBy { it.lowercase() }
        require(photoIds.isNotEmpty() || categories.isNotEmpty() || tags.isNotEmpty()) {
            "智能相册至少需要照片、分类或标签中的一项规则"
        }

        return albumStore.save(
            SmartAlbum(
                name = name,
                rule = SmartAlbumRule(
                    photoIds = photoIds,
                    categories = categories,
                    tags = tags,
                    matchAllTags = input.matchAllTags
                ),
                createdBy = input.createdBy.trim().ifBlank { "user" }
            )
        )
    }

    private fun cleanTerm(raw: String): String? = raw.trim().replace(WHITESPACE, " ")
        .takeIf(String::isNotEmpty)
        ?.also { require(it.length <= MAX_RULE_TERM_LENGTH) { "相册规则过长" } }

    private fun cleanTag(raw: String): String? = cleanTerm(raw.removePrefix("#"))?.let { "#$it" }

    override fun getToolDescription(): String = """
        |## 工具名称
        |create_smart_album
        |
        |## 功能
        |创建本地智能相册。可固定当前照片，也可保存分类/标签规则以便新增照片自动匹配。
        |
        |## 输入参数
        |- name (String, 必填): 相册名称
        |- photo_ids (Array<String>, 可选): 当前确认的照片
        |- categories (Array<String>, 可选): 分类规则
        |- tags (Array<String>, 可选): 标签规则
        |- match_all_tags (Boolean, 可选): 多标签时是否必须全部满足
        |
        |## 输出
        |SmartAlbum：id、name、rule、createdAt。
    """.trimMargin()

    private companion object {
        const val MAX_NAME_LENGTH = 60
        const val MAX_RULE_TERM_LENGTH = 48
        val WHITESPACE = Regex("\\s+")
    }
}
