package com.example.isip.ui.model

data class PhotoUiModel(
    val id: String,
    val uri: String,
    val takenAtText: String,
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val hasPrivacyAlert: Boolean = false,
    val isAnalyzed: Boolean = false,
    val sizeBytes: Long = 0L,
    val width: Int = 0,
    val height: Int = 0
)

data class SearchResultUiModel(
    val photo: PhotoUiModel,
    val relevanceScoreText: String,
    val matchedTags: List<String> = emptyList(),
    val matchedText: String? = null
)

data class AnalysisProgressUi(
    val total: Int,
    val completed: Int,
    val currentTaskText: String,
    val progress: Float,
    val canPause: Boolean = true,
    val canCancel: Boolean = true
)

data class OrganizationPlanUiModel(
    val categorySuggestions: List<CategorySuggestion> = emptyList(),
    val duplicateGroups: List<DuplicateGroup> = emptyList(),
    val similarGroups: List<SimilarGroupUi> = emptyList(),
    val tagSuggestions: List<TagSuggestionUi> = emptyList(),
    val privacyAlerts: List<PrivacyAlert> = emptyList(),
    val suggestions: List<String> = emptyList()
)

data class SimilarGroupUi(
    val id: String,
    val photos: List<PhotoUiModel>,
    val similarityScore: Float,
    val reason: String
)

data class TagSuggestionUi(
    val photo: PhotoUiModel,
    val tags: List<String>,
    val reason: String?
)

data class SmartAlbumUiModel(
    val id: Long,
    val name: String,
    val photos: List<PhotoUiModel>,
    val photoCount: Int,
    val ruleDescription: String
)

data class CategorySuggestion(
    val id: String,
    val categoryName: String,
    val photoCount: Int,
    val description: String,
    val photos: List<PhotoUiModel> = emptyList()
)

data class DuplicateGroup(
    val id: String,
    val photos: List<PhotoUiModel>,
    val similarityScore: Float,
    val recommendedKeepId: String
)

data class DuplicateComparisonUi(
    val group: DuplicateGroup,
    val selectedKeepId: String
)

data class PrivacyAlert(
    val id: String,
    val photo: PhotoUiModel,
    val alertType: String,
    val description: String,
    val severity: PrivacySeverity
)

enum class PrivacySeverity {
    LOW, MEDIUM, HIGH
}

data class BatchActionPreviewUi(
    val actionType: String,
    val affectedPhotoCount: Int,
    val affectedPhotos: List<PhotoUiModel>,
    val description: String
)
