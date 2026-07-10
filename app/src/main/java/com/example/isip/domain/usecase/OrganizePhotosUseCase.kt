package com.example.photoagent.domain.usecase

import com.example.photoagent.data.dao.AnalysisResultDao
import com.example.photoagent.data.model.ImageAnalysisResult
import com.example.photoagent.data.model.OrganizationPlan
import com.example.photoagent.domain.skill.GenerateStrategySkill

/**
 * 整理照片用例
 * 生成相册整理方案
 */
class OrganizePhotosUseCase(
    private val analysisResultDao: AnalysisResultDao,
    private val generateStrategySkill: GenerateStrategySkill
) {

    /**
     * 生成整理方案
     * @param photoIds 指定照片ID列表（可选，不传则整理所有已分析照片）
     */
    suspend fun generateOrganizationPlan(photoIds: List<String>? = null): OrganizationPlan {
        val analyses = if (photoIds != null) {
            photoIds.mapNotNull { analysisResultDao.getByPhotoId(it) }
        } else {
            analysisResultDao.getAll()
        }

        val input = GenerateStrategySkill.Input(analyses)
        return generateStrategySkill.execute(input)
    }

    /**
     * 获取整理建议摘要
     */
    suspend fun getOrganizationSummary(): OrganizationSummary {
        val plan = generateOrganizationPlan()

        return OrganizationSummary(
            totalPhotos = plan.albums.sumOf { it.photoIds.size },
            albumCount = plan.albums.size,
            duplicateCount = plan.duplicates.sumOf { it.photoIds.size },
            privacyCount = plan.privacyRisks.size,
            suggestions = plan.suggestions
        )
    }

    /**
     * 应用整理方案（执行清理操作）
     */
    suspend fun applyOrganizationPlan(plan: OrganizationPlan, confirmations: OrganizationConfirmations): Boolean {
        // TODO: 实现整理方案的应用
        // 1. 删除重复照片（用户确认的）
        // 2. 创建相册
        // 3. 移动/加密隐私照片
        return true
    }
}

/**
 * 整理摘要
 */
data class OrganizationSummary(
    val totalPhotos: Int,
    val albumCount: Int,
    val duplicateCount: Int,
    val privacyCount: Int,
    val suggestions: List<String>
)

/**
 * 用户确认项
 */
data class OrganizationConfirmations(
    val confirmedDuplicates: List<String>,       // 确认删除的重复组ID
    val confirmedPrivacy: List<String>,          // 确认处理的隐私照片ID
    val albumNames: Map<String, String>          // 相册ID → 自定义名称
)