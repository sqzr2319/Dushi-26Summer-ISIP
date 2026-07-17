package com.example.isip.data.model

/** Metadata required by duplicate-video detection. */
data class VideoAsset(
    val id: String,
    val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val dateTaken: Long,
    /** Optional exact content hash. Equal non-null hashes are exact duplicates. */
    val contentHash: String? = null
)

data class DuplicateVideoGroup(
    val id: String,
    val videoIds: List<String>,
    val recommendKeep: String,
    val similarity: Float,
    val totalBytes: Long,
    val reclaimableBytes: Long,
    val reason: String
)
