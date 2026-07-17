package com.example.isip.domain.usecase

import com.example.isip.data.PhotoRepository
import com.example.isip.data.model.Photo
import com.example.isip.data.model.SmartAlbum
import com.example.isip.domain.skill.CreateSmartAlbumSkill

/** 创建、读取和删除本地智能相册规则。 */
class SmartAlbumUseCase(private val photoRepository: PhotoRepository) {
    private val createSkill = CreateSmartAlbumSkill(
        CreateSmartAlbumSkill.AlbumStore(photoRepository::saveSmartAlbum)
    )

    suspend fun create(input: CreateSmartAlbumSkill.Input): SmartAlbum = createSkill.execute(input)

    suspend fun getAll(): List<SmartAlbum> = photoRepository.getSmartAlbums()

    /** 按已保存规则解析相册成员；不会修改系统相册或移动照片文件。 */
    suspend fun resolvePhotos(album: SmartAlbum): List<Photo> {
        val photos = photoRepository.getAllPhotos()
        val analysisByPhotoId = photoRepository.getAllAnalysisResults().associateBy { it.photoId }
        val rule = album.rule
        val fixedIds = rule.photoIds.toSet()
        val categoryRules = rule.categories.map(::normalize).toSet()
        val tagRules = rule.tags.map(::normalize).toSet()
        val hasDynamicRules = categoryRules.isNotEmpty() || tagRules.isNotEmpty()

        return photos.filter { photo ->
            if (photo.id in fixedIds) return@filter true
            if (!hasDynamicRules) return@filter false

            val analysis = analysisByPhotoId[photo.id] ?: return@filter false
            val categoriesMatch = categoryRules.isEmpty() || analysis.categories
                .map(::normalize).any { it in categoryRules }
            val photoTags = analysis.tags.map(::normalize).toSet()
            val tagsMatch = when {
                tagRules.isEmpty() -> true
                rule.matchAllTags -> tagRules.all { it in photoTags }
                else -> tagRules.any { it in photoTags }
            }
            categoriesMatch && tagsMatch
        }
    }

    suspend fun delete(id: Long) = photoRepository.deleteSmartAlbum(id)

    private fun normalize(term: String): String = term.trim().removePrefix("#").lowercase()
}
