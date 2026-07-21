package com.example.isip.data.ai

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import android.net.Uri
import com.example.isip.data.model.Photo
import com.example.isip.domain.skill.AnalyzeImageSkill
import com.example.isip.domain.skill.FindSimilarPhotosSkill
import com.example.isip.domain.skill.GenerateStrategySkill
import com.example.isip.domain.skill.SearchPhotosSkill
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * MobileCLIP 的 Android/LiteRT 实现。
 *
 * 模型放在 files/clip 下，避免把约 400MB 权重打进 APK：
 * mobileclip_image.tflite、mobileclip_text.tflite、vocab.json、merges.txt。
 * 两个 encoder 的输出必须来自同一个 checkpoint，且均为同维度向量。
 */
class MobileClipEngine private constructor(
    context: Context,
    private val modelDir: File
) : AnalyzeImageSkill.ClipImageAnalyzer,
    SearchPhotosSkill.ClipSearchEngine,
    GenerateStrategySkill.ClipSimilarityEngine,
    FindSimilarPhotosSkill.SimilarityEngine,
    AutoCloseable {

    private val appContext = context.applicationContext
    private val imageInterpreter = Interpreter(File(modelDir, IMAGE_MODEL))
    private val textInterpreter = Interpreter(File(modelDir, TEXT_MODEL))
    private val tokenizer = ClipBpeTokenizer(
        vocabFile = File(modelDir, VOCAB_FILE),
        mergesFile = File(modelDir, MERGES_FILE),
        contextLength = textInterpreter.getInputTensor(0).shape().last()
    )
    private val embeddingDir = File(modelDir, EMBEDDING_DIR).apply { mkdirs() }
    private val inferenceMutex = Mutex()

    override suspend fun analyze(photo: Photo): AnalyzeImageSkill.ClipAnalysis = withContext(Dispatchers.IO) {
        val imageEmbedding = imageEmbedding(photo)
        val categoryScores = CATEGORY_PROMPTS.map { prompt ->
            prompt.category to cosine(imageEmbedding, textEmbedding(prompt.prompt))
        }.sortedByDescending { it.second }
        val probabilities = softmax(categoryScores.map { it.second * LOGIT_SCALE })
        val bestProbability = probabilities.firstOrNull() ?: 0f
        val categories = categoryScores.zip(probabilities)
            .filterIndexed { index, (_, probability) -> index == 0 || probability >= SECONDARY_CATEGORY_THRESHOLD }
            .take(MAX_CATEGORIES).map { it.first.first }
        val labels = categoryScores.zip(probabilities).take(MAX_LABELS).map { (item, probability) ->
            VisualLabel(item.first, probability)
        }
        AnalyzeImageSkill.ClipAnalysis(
            categories = categories,
            labels = labels,
            confidence = bestProbability.coerceIn(0f, 1f),
            embeddingPath = embeddingFile(photo.id).absolutePath,
            modelName = MODEL_NAME,
            modelVersion = MODEL_VERSION
        )
    }

    override suspend fun search(
        query: String,
        candidatePhotoIds: Set<String>,
        limit: Int
    ): List<SearchPhotosSkill.ClipMatch> = withContext(Dispatchers.IO) {
        val queryEmbedding = textEmbedding(query)
        candidatePhotoIds.mapNotNull { photoId ->
            readEmbedding(photoId)?.let { vector ->
                val raw = cosine(queryEmbedding, vector)
                SearchPhotosSkill.ClipMatch(
                    photoId = photoId,
                    relevanceScore = calibrateSimilarity(raw),
                    explanation = "MobileCLIP 语义相似度 %.3f".format(raw)
                )
            }
        }.sortedByDescending(SearchPhotosSkill.ClipMatch::relevanceScore).take(limit)
    }

    override suspend fun findSimilar(
        photoIds: Set<String>,
        threshold: Float
    ): List<GenerateStrategySkill.SimilarPair> = withContext(Dispatchers.IO) {
        // 全量两两比较是 O(n²)，在真实相册中会造成长时间卡顿甚至被系统杀进程。
        // 元数据重复检测仍覆盖全量照片；视觉向量只对一个有界集合做增强检测。
        val vectors = photoIds.asSequence()
            .mapNotNull { id -> readEmbedding(id)?.let { id to it } }
            .take(MAX_VISUAL_DUPLICATE_SCAN)
            .toList()
        buildList {
            for (first in vectors.indices) for (second in first + 1 until vectors.size) {
                val similarity = cosine(vectors[first].second, vectors[second].second)
                if (similarity >= threshold) add(
                    GenerateStrategySkill.SimilarPair(
                        vectors[first].first, vectors[second].first, similarity
                    )
                )
            }
        }
    }

    override suspend fun findSimilar(
        targetPhotoId: String,
        candidatePhotoIds: Set<String>,
        limit: Int
    ): List<FindSimilarPhotosSkill.SimilarPhoto> = withContext(Dispatchers.IO) {
        val target = readEmbedding(targetPhotoId) ?: return@withContext emptyList()
        candidatePhotoIds.asSequence()
            .filterNot { it == targetPhotoId }
            .mapNotNull { photoId ->
                readEmbedding(photoId)?.let { vector ->
                    val similarity = cosine(target, vector).coerceIn(0f, 1f)
                    FindSimilarPhotosSkill.SimilarPhoto(
                        photoId = photoId,
                        similarity = similarity,
                        explanation = "MobileCLIP 图像相似度 %.3f".format(similarity)
                    )
                }
            }
            .sortedByDescending(FindSimilarPhotosSkill.SimilarPhoto::similarity)
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    /** 创建或读取图片向量；重复分析不会再次运行模型。 */
    suspend fun imageEmbedding(photoId: String, force: Boolean = false): FloatArray =
        withContext(Dispatchers.IO) {
            if (!force) readEmbedding(photoId)?.let { return@withContext it }
            val bitmap = decodePhoto(photoId)
            try {
                val inputTensor = imageInterpreter.getInputTensor(0)
                val shape = inputTensor.shape()
                require(shape.size == 4 && shape[0] == 1) { "CLIP 图片输入必须为 4 维 batch tensor" }
                val nchw = shape[1] == 3
                val height = if (nchw) shape[2] else shape[1]
                val width = if (nchw) shape[3] else shape[2]
                val input = preprocessImage(bitmap, width, height, nchw)
                val embedding = inferenceMutex.withLock { runFloatModel(imageInterpreter, input) }
                normalizeInPlace(embedding)
                writeEmbedding(photoId, embedding)
                embedding
            } finally {
                bitmap.recycle()
            }
        }

    private suspend fun imageEmbedding(photo: Photo, force: Boolean = false): FloatArray =
        withContext(Dispatchers.IO) {
            if (!force) readEmbedding(photo.id)?.let { return@withContext it }
            val bitmap = decodePhoto(photo)
            try {
                val inputTensor = imageInterpreter.getInputTensor(0)
                val shape = inputTensor.shape()
                require(shape.size == 4 && shape[0] == 1) { "CLIP 图片输入必须为 4 维 batch tensor" }
                val nchw = shape[1] == 3
                val height = if (nchw) shape[2] else shape[1]
                val width = if (nchw) shape[3] else shape[2]
                val input = preprocessImage(bitmap, width, height, nchw)
                val embedding = inferenceMutex.withLock { runFloatModel(imageInterpreter, input) }
                normalizeInPlace(embedding)
                writeEmbedding(photo.id, embedding)
                embedding
            } finally {
                bitmap.recycle()
            }
        }

    suspend fun textEmbedding(text: String): FloatArray = withContext(Dispatchers.Default) {
        val inputTensor = textInterpreter.getInputTensor(0)
        val tokenIds = tokenizer.encode(text)
        val input: Any = when (inputTensor.dataType()) {
            DataType.INT64 -> LongBuffer.wrap(tokenIds.map(Int::toLong).toLongArray())
            DataType.INT32 -> IntBuffer.wrap(tokenIds)
            else -> error("CLIP 文本输入仅支持 INT32/INT64，实际为 ${inputTensor.dataType()}")
        }
        val embedding = inferenceMutex.withLock { runFloatModel(textInterpreter, input) }
        normalizeInPlace(embedding)
        embedding
    }

    private fun runFloatModel(interpreter: Interpreter, input: Any): FloatArray {
        val outputTensor = interpreter.getOutputTensor(0)
        require(outputTensor.dataType() == DataType.FLOAT32) { "CLIP 输出必须为 FLOAT32" }
        val count = outputTensor.shape().fold(1, Int::times)
        val output = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()
        return FloatArray(count).also { output.asFloatBuffer().get(it) }
    }

    private fun preprocessImage(bitmap: Bitmap, width: Int, height: Int, nchw: Boolean): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== bitmap) scaled.recycle()
        val buffer = ByteBuffer.allocateDirect(width * height * 3 * 4).order(ByteOrder.nativeOrder())
        if (nchw) {
            for (channel in 0..2) pixels.forEach { pixel -> buffer.putFloat(channel(pixel, channel) / 255f) }
        } else {
            pixels.forEach { pixel -> for (channel in 0..2) buffer.putFloat(channel(pixel, channel) / 255f) }
        }
        buffer.rewind()
        return buffer
    }

    private fun channel(pixel: Int, channel: Int): Float = when (channel) {
        0 -> ((pixel shr 16) and 0xff).toFloat()
        1 -> ((pixel shr 8) and 0xff).toFloat()
        else -> (pixel and 0xff).toFloat()
    }

    private fun decodePhoto(photoId: String): Bitmap {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId.toLong())
        return appContext.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: error("无法读取照片 $photoId")
    }

    private fun decodePhoto(photo: Photo): Bitmap {
        val stored = photo.filePath.trim().takeIf(String::isNotEmpty)?.let(Uri::parse)
        val uri = when {
            stored?.scheme == "content" || stored?.scheme == "file" -> stored
            stored != null && stored.scheme == null -> Uri.fromFile(File(photo.filePath))
            else -> ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                photo.id.toLongOrNull() ?: error("无效照片 ID: ${photo.id}")
            )
        }
        return appContext.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: error("无法打开照片 ${photo.id}，请检查照片访问权限")
    }

    private fun embeddingFile(photoId: String) = File(embeddingDir, "${safeId(photoId)}.f32")
    private fun safeId(photoId: String) = photoId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun writeEmbedding(photoId: String, values: FloatArray) {
        DataOutputStream(BufferedOutputStream(embeddingFile(photoId).outputStream())).use { output ->
            output.writeInt(values.size)
            values.forEach(output::writeFloat)
        }
    }

    private fun readEmbedding(photoId: String): FloatArray? {
        val file = embeddingFile(photoId)
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
                val size = input.readInt()
                require(size in 1..MAX_EMBEDDING_SIZE)
                FloatArray(size) { input.readFloat() }
            }
        }.getOrNull()
    }

    override fun close() {
        imageInterpreter.close()
        textInterpreter.close()
    }

    companion object {
        const val MODEL_NAME = "mobileclip-s2-litert"
        private const val MAX_VISUAL_DUPLICATE_SCAN = 500
        const val MODEL_VERSION = "datacompdr"
        private const val IMAGE_MODEL = "mobileclip_image.tflite"
        private const val TEXT_MODEL = "mobileclip_text.tflite"
        private const val VOCAB_FILE = "vocab.json"
        private const val MERGES_FILE = "merges.txt"
        private const val EMBEDDING_DIR = "embeddings"
        private const val MAX_EMBEDDING_SIZE = 4096
        private const val LOGIT_SCALE = 20f
        private const val SECONDARY_CATEGORY_THRESHOLD = 0.16f
        private const val MAX_CATEGORIES = 2
        private const val MAX_LABELS = 5

        private data class CategoryPrompt(val category: String, val prompt: String)
        private val CATEGORY_PROMPTS = listOf(
            CategoryPrompt("人物", "a photo of people or a portrait"),
            CategoryPrompt("风景", "a landscape or nature photo"),
            CategoryPrompt("美食", "a photo of food or a meal"),
            CategoryPrompt("截图", "a smartphone screenshot"),
            CategoryPrompt("文档", "a photo of a document with text"),
            CategoryPrompt("票据", "a receipt or invoice"),
            CategoryPrompt("证件", "an identity card or official document"),
            CategoryPrompt("宠物", "a photo of a pet or animal"),
            CategoryPrompt("出行", "a travel photo or vehicle"),
            CategoryPrompt("其他", "an ordinary miscellaneous photo")
        )

        fun createOrNull(context: Context): MobileClipEngine? {
            val dir = File(context.filesDir, "clip")
            val required = listOf(IMAGE_MODEL, TEXT_MODEL, VOCAB_FILE, MERGES_FILE)
            return if (required.all { File(dir, it).isFile }) MobileClipEngine(context, dir) else null
        }

        fun modelDirectory(context: Context): File = File(context.filesDir, "clip")

        internal fun cosine(left: FloatArray, right: FloatArray): Float {
            if (left.size != right.size || left.isEmpty()) return 0f
            var dot = 0f; var l2 = 0f; var r2 = 0f
            for (index in left.indices) {
                dot += left[index] * right[index]
                l2 += left[index] * left[index]
                r2 += right[index] * right[index]
            }
            val denominator = sqrt(l2) * sqrt(r2)
            return if (denominator > 0f) dot / denominator else 0f
        }

        private fun normalizeInPlace(vector: FloatArray) {
            val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
            if (norm > 0f) vector.indices.forEach { vector[it] /= norm }
        }

        private fun calibrateSimilarity(value: Float): Float = ((value - 0.1f) / 0.3f).coerceIn(0f, 1f)

        private fun softmax(values: List<Float>): List<Float> {
            val max = values.maxOrNull() ?: return emptyList()
            val exps = values.map { exp((it - max).toDouble()).toFloat() }
            val sum = exps.sum().takeIf { it > 0f } ?: return values.map { 0f }
            return exps.map { it / sum }
        }
    }
}

/** 全 App 共享两个 Interpreter，避免每个 ViewModel 重复占用数百 MB 内存。 */
object MobileClipProvider {
    @Volatile private var instance: MobileClipEngine? = null

    fun getOrNull(context: Context): MobileClipEngine? = instance ?: synchronized(this) {
        instance ?: MobileClipEngine.createOrNull(context)?.also { instance = it }
    }

    /** 返回已加载实例，不在当前页面同步创建数百 MB 的解释器。 */
    fun peekOrNull(): MobileClipEngine? = instance
}

/** OpenAI CLIP byte-level BPE tokenizer, compatible with MobileCLIP context length 77. */
internal class ClipBpeTokenizer(
    vocabFile: File,
    mergesFile: File,
    private val contextLength: Int
) {
    private val vocab: Map<String, Int> = Gson().fromJson(
        vocabFile.readText(), object : TypeToken<Map<String, Int>>() {}.type
    )
    private val ranks = mergesFile.readLines().asSequence().filterNot { it.startsWith("#") }
        .map(String::trim).filter(String::isNotEmpty).mapIndexedNotNull { index, line ->
            val parts = line.split(' ')
            if (parts.size == 2) (parts[0] to parts[1]) to index else null
        }.toMap()
    private val cache = mutableMapOf<String, List<String>>()
    private val byteEncoder = byteEncoder()
    private val startToken = requireNotNull(vocab["<start_of_text>"] ?: vocab["<|startoftext|>"])
    private val endToken = requireNotNull(vocab["<end_of_text>"] ?: vocab["<|endoftext|>"])
    private val pattern = Regex("<\\|startoftext\\|>|<\\|endoftext\\|>|[A-Za-z]+|[0-9]+|[^\\sA-Za-z0-9]+")

    fun encode(text: String): IntArray {
        val pieces = pattern.findAll(text.lowercase()).flatMap { match ->
            val encoded = match.value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
                byteEncoder[byte.toInt() and 0xff].toString()
            }
            bpe(encoded).asSequence()
        }.mapNotNull(vocab::get).take(contextLength - 2).toList()
        return IntArray(contextLength).also { ids ->
            ids[0] = startToken
            pieces.forEachIndexed { index, token -> ids[index + 1] = token }
            ids[pieces.size + 1] = endToken
        }
    }

    private fun bpe(token: String): List<String> = cache.getOrPut(token) {
        var word = token.dropLast(1).map(Char::toString) + "${token.last()}</w>"
        while (word.size > 1) {
            val pair = word.zipWithNext().minByOrNull { ranks[it] ?: Int.MAX_VALUE } ?: break
            if (pair !in ranks) break
            val merged = mutableListOf<String>()
            var index = 0
            while (index < word.size) {
                if (index < word.lastIndex && word[index] == pair.first && word[index + 1] == pair.second) {
                    merged += pair.first + pair.second; index += 2
                } else merged += word[index++]
            }
            word = merged
        }
        word
    }

    private fun byteEncoder(): Array<Char> {
        val bytes = ((33..126) + (161..172) + (174..255)).toMutableList()
        val chars = bytes.toMutableList()
        var extra = 0
        for (byte in 0..255) if (byte !in bytes) {
            bytes += byte; chars += 256 + extra++
        }
        return Array(256) { index -> chars[bytes.indexOf(index)].toChar() }
    }
}
