package com.example.isip.ui.model

data class PhotoUiModel(
    val id: String,
    val uri: String,
    val takenAtText: String,
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val hasPrivacyAlert: Boolean = false,
    val isAnalyzed: Boolean = false
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
    val privacyAlerts: List<PrivacyAlert> = emptyList()
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
