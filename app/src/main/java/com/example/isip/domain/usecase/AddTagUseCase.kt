package com.example.isip.domain.usecase

import com.example.isip.data.PhotoRepository
import com.example.isip.domain.skill.AddTagSkill

/** UI/Agent 调用手动标签 Skill 的入口。 */
class AddTagUseCase(photoRepository: PhotoRepository) {
    private val skill = AddTagSkill(
        AddTagSkill.TagStore { photoIds, tags, source ->
            photoRepository.addManualTags(photoIds, tags, source)
        }
    )

    suspend fun addTags(photoIds: List<String>, tags: List<String>): AddTagSkill.Output =
        skill.execute(AddTagSkill.Input(photoIds = photoIds, tags = tags))
}
