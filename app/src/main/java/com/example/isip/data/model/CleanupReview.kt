package com.example.isip.data.model

enum class CleanupCandidateType {
    DUPLICATE_PHOTO,
    DUPLICATE_VIDEO,
    LOW_QUALITY,
    SCREENSHOT,
    LARGE_FILE,
    OTHER
}

/** A recommendation only. It must be reviewed before it can become a delete request. */
data class CleanupCandidate(
    val id: String,
    val type: CleanupCandidateType,
    val mediaIds: List<String>,
    val recommendedKeepId: String? = null,
    val reason: String,
    val confidence: Float,
    val totalBytes: Long = 0L
)

data class ReviewedCleanupItem(
    val candidateId: String,
    val type: CleanupCandidateType,
    val keepId: String?,
    val deleteIds: List<String>,
    val reclaimableBytes: Long,
    val reason: String,
    val warnings: List<String>
)

data class CleanupReview(
    val items: List<ReviewedCleanupItem>,
    val deleteIds: List<String>,
    val reclaimableBytes: Long,
    val warnings: List<String>,
    val requiresUserConfirmation: Boolean = true
)
