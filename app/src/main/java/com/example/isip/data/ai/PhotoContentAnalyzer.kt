package com.example.isip.data.ai

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.util.Base64
import com.example.isip.BuildConfig
import com.example.isip.data.model.Photo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

/** A model-neutral contract for creating the persistent analysis record of a photo. */
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

/**
 * Sends a compressed photo to the local Qwen3.5-VL service and receives only
 * structured metadata. Qwen is never called while the user searches.
 *
 * The default endpoint is Android Emulator's alias for the host computer. A
 * physical device must use the computer's LAN address through a Gradle field.
 */
class Qwen35PhotoContentAnalyzer(
    context: Context,
    private val endpoint: String = BuildConfig.QWEN35_ANALYSIS_URL
) : PhotoContentAnalyzer {
    private val appContext = context.applicationContext

    override val modelName: String = "qwen3.5-vl"
    override val modelVersion: String = "Qwen3.5-4B"

    override suspend fun analyze(photo: Photo): PhotoContentAnalysis = withContext(Dispatchers.IO) {
        val payload = JsonObject().apply {
            addProperty("image_base64", encodePhoto(photo.id))
            addProperty("mime_type", "image/jpeg")
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val responseBody = if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: "HTTP ${connection.responseCode}"
                throw IllegalStateException("Qwen3.5-VL analysis failed: $error")
            }
            return@withContext responseBody.toAnalysis()
        } finally {
            connection.disconnect()
        }
    }

    private fun encodePhoto(photoId: String): String {
        val uri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            photoId.toLong()
        )
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        appContext.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: throw IllegalStateException("Unable to open photo $photoId")

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = appContext.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: throw IllegalStateException("Unable to decode photo $photoId")

        val longestSide = max(decoded.width, decoded.height)
        val scaled = if (longestSide > MAX_IMAGE_SIDE) {
            val scale = MAX_IMAGE_SIDE.toFloat() / longestSide
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            decoded
        }

        return try {
            ByteArrayOutputStream().use { bytes ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bytes))
                Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        var longestSide = max(width, height)
        while (longestSide / 2 >= MAX_IMAGE_SIDE * 2) {
            sampleSize *= 2
            longestSide /= 2
        }
        return sampleSize
    }

    private fun String.toAnalysis(): PhotoContentAnalysis {
        val root = JsonParser.parseString(this).asJsonObject
        val tags = root.stringList("tags")
        return PhotoContentAnalysis(
            labels = tags.map { VisualLabel(it, root.float("confidence", DEFAULT_CONFIDENCE)) },
            categories = root.stringList("categories"),
            tags = tags,
            description = root.string("description"),
            ocrText = root.string("ocr_text"),
            confidence = root.float("confidence", DEFAULT_CONFIDENCE)
        )
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString?.trim().orEmpty()

    private fun JsonObject.float(name: String, default: Float): Float =
        get(name)?.takeUnless { it.isJsonNull }?.asFloat?.coerceIn(0f, 1f) ?: default

    private fun JsonObject.stringList(name: String): List<String> =
        getAsJsonArray(name)?.mapNotNull { element ->
            element.takeUnless { it.isJsonNull }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        }?.distinct()?.take(MAX_RETURNED_TERMS).orEmpty()

    private companion object {
        const val MAX_IMAGE_SIDE = 1280
        const val JPEG_QUALITY = 85
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 300_000
        const val DEFAULT_CONFIDENCE = 0.7f
        const val MAX_RETURNED_TERMS = 8
    }
}
