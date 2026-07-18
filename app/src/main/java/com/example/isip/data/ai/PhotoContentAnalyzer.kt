package com.example.isip.data.ai

import com.example.isip.data.model.Photo

/** A model-neutral contract for creating a persistent analysis record of a photo. */
interface PhotoContentAnalyzer {
    val modelName: String
    val modelVersion: String

    suspend fun analyze(photo: Photo): PhotoContentAnalysis
}

data class VisualLabel(
    val text: String,
    val confidence: Float
)

data class PhotoContentAnalysis(
    val labels: List<VisualLabel> = emptyList(),
    val ocrText: String = "",
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val description: String = "",
    val confidence: Float = 0.7f
)
