package com.example.isip.data.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The two GGUF files that make up the on-device Qwen3.5 vision-language model.
 *
 * Both files ship inside the APK under `assets/models/`. llama.cpp needs a real
 * filesystem path (it maps the file and reads it in chunks), so the files are
 * copied into the app's private directory on first use and reused afterwards.
 *
 * Keeping the file names in one place matters: the asset names and the names the
 * inference layer looks for used to disagree (`Qwen3.5-2B-UD-Q2_K_XL.gguf` in
 * assets vs `Qwen3.5-2B_Q4_K_M.gguf` in code), which made every analysis silently
 * fall back to the rule-based path.
 */
object QwenModel {
    /**
     * 主模型候选，按优先级排列。
     *
     * 优先 Q4_0：llama.cpp 的 Hexagon NPU 后端只接受
     * Q4_0/Q4_1/Q8_0/IQ4_NL/MXFP4/F16/F32 作为 matmul 的权重类型，K-quant 一律
     * 回落到 CPU。所以要用 NPU 就必须是 Q4_0。
     *
     * **2B 排在 4B 前面，这是速度与质量权衡后的决定。** 两个档位输出的**正确性**
     * 没有差别（都经过真实相册照片验证），差别只在细节颗粒度和耗时：
     *
     * | | 2B-Q4_0 | 4B-Q4_0 |
     * | --- | --- | --- |
     * | 单张真实照片（768px / NPU） | **58–69 s** | 127–165 s |
     * | NPU 驻留权重 | 380 MiB（25/25 层） | 951 MiB（33/33 层） |
     * | 6,848 张非截图照片的批量耗时 | 约 5 天 | 约 12 天 |
     * | 具体地标 | "密集的高楼大厦" | 能说出 IFC、中银大厦 |
     *
     * 4B 的额外能力（认得具体楼名、更细的分层叙述）不值得 2 倍以上的耗时，
     * 尤其是相册分析是个几千张的批量任务。4B 保留在次位：万一 2B 缺失，
     * 宁可慢也不要不可用。
     *
     * 末位是 APK 内打包的那一份，K-quant，只能跑 CPU —— 作为最后的兜底。
     *
     * ⚠️ **切换档位必须连同 mmproj 一起换**：2B 与 4B 的视觉塔同名但内容不同，
     * 只换主模型会留下不配套的视觉塔。用 `tools/deploy_qwen35.py --variant 2b|4b`
     * 成对部署，它会按字节数校验落地结果；运行时的
     * [verifyMmprojMatches] 也会再拦一道。
     */
    val MODEL_FILE_CANDIDATES = listOf(
        "Qwen3.5-0.8B-Q4_0.gguf",        // 首选（按需求方选择）：快一倍，代价见下
        "Qwen3.5-2B-Q4_0.gguf",          // 回退：0 观察到幻觉，但慢一倍
        "Qwen3.5-4B-Q4_0.gguf",          // 质量更细但更慢
        "Qwen3.5-2B-UD-Q2_K_XL.gguf",    // 末位回退：K-quant，NPU 上会逐算子回落 CPU
    )

    /** 向后兼容的默认名（等于首选）。 */
    const val MODEL_FILE_NAME = "Qwen3.5-0.8B-Q4_0.gguf"

    /**
     * Qwen3.5-0.8B 的实测结论（2026-09-13，512px，NPU，31 张真实相册照片）：
     *
     * **速度确实快一倍，但错误率约 13%，因此不是默认首选。**
     *
     * | 指标 | 0.8B | 2B |
     * | --- | --- | --- |
     * | 单张耗时（设备正常时） | **7.5–10.5 s** | 17–20 s |
     * | 视觉塔编码 | 2 s（mmproj 196 MB） | 6 s（668 MB） |
     * | decode | 22.6–24.0 tok/s | 13.4–15.9 tok/s |
     * | NPU 驻留权重 | 135 MiB | 380 MiB |
     * | 地名幻觉 | 有：`#港珠澳大桥`（照片是维港） | 未观察到 |
     * | 内容误判 | 有：烧鹅饭 → "日式料理…梅干菜" | 未观察到 |
     * | 分类矛盾 | 有：动漫角色 → `[风景, 集体合影]` | 未观察到 |
     * | JSON 格式偏差 | 有：tags 输出成字符串而非数组（已在解析侧兼容） | 未观察到 |
     * | **OCR** | **明显更强**：读出菜单、书籍封面、游戏界面 `ENERGY GEN 30`、甚至整段问答文本 | 中等 |
     *
     * 所以 0.8B 不是"更差"，而是**长板更长、短板更短**：它适合以 OCR/文字为主的截图，
     * 但会编造具体名词（地标、菜系），而错误标签会直接污染搜索与分类。
     * 要重新启用就成对部署（`tools/deploy_qwen35.py --variant 0.8b`），
     * 注意它的 mmproj 与 2B **同名不同内容**。
     */
    const val NOTES_0_8B = ""

    /**
     * Multimodal projection (vision encoder)。
     *
     * 注意：2B 与 4B 的 mmproj **文件名相同**（都叫 mmproj-F16.gguf），但内容不同
     * （2B 的 668,227,264 B，4B 的 672,423,616 B）。部署时必须与主模型成对替换，
     * 只换主模型会留下不配套的视觉塔。两者同出自 unsloth/Qwen3.5-*-GGUF，
     * 各自 revision 内配对是有保证的。
     */
    const val MMPROJ_FILE_NAME = "mmproj-F16.gguf"

    /**
     * 各主模型档位对应的 mmproj 精确字节数。
     *
     * **这是一道防"静默产出垃圾"的校验，不是洁癖。** 2B 与 4B 的视觉塔**同名**
     * （都叫 `mmproj-F16.gguf`）但内容不同，而且 APK 的 `assets/models/` 里打包的是
     * **2B 那一份**。于是存在这样一条真实路径：
     *
     * 1. 设备上只有 4B 主模型（例如手工推送了主模型、或上次部署被中断）；
     * 2. `ensureAvailable(MMPROJ_FILE_NAME)` 发现私有目录没有，就从 assets 复制出 2B 的；
     * 3. 4B 主模型 + 2B 视觉塔 → 投影维度对不上，**模型照跑，只是输出无意义**。
     *
     * 这正是 `tools/deploy_qwen35.py` 按字节数校验的原因；但那只覆盖"部署"这一个入口，
     * 覆盖不了 App 首次运行时的自我修复路径。所以运行时也要拦一次。
     *
     * 字节数来自 HuggingFace unsloth/Qwen3.5-{2B,4B}-GGUF 各自 revision。
     */
    val MMPROJ_SIZE_BY_MODEL: Map<String, Long> = mapOf(
        "Qwen3.5-0.8B-Q4_0.gguf" to 204_987_232L,
        "Qwen3.5-2B-Q4_0.gguf" to 668_227_264L,
        "Qwen3.5-4B-Q4_0.gguf" to 672_423_616L,
        // Q2_K_XL 是 2B 的另一种量化，视觉塔与 2B 相同
        "Qwen3.5-2B-UD-Q2_K_XL.gguf" to 668_227_264L,
    )

    /**
     * 校验 mmproj 与主模型是否配套。
     *
     * @return null 表示配套或无法判断（该主模型不在已知表里）；否则返回人话描述的
     *         不匹配原因，调用方应据此判定初始化失败而不是继续推理。
     */
    fun verifyMmprojMatches(modelFileName: String, mmprojPath: String): String? {
        val expected = MMPROJ_SIZE_BY_MODEL[modelFileName] ?: return null
        val file = File(mmprojPath)
        if (!file.isFile) return "mmproj 不存在: $mmprojPath"
        val actual = file.length()
        if (actual == expected) return null
        return "mmproj 与主模型不配套：$modelFileName 需要 $expected 字节的视觉塔，" +
            "实际是 $actual 字节。2B 与 4B 的 mmproj 同名但内容不同，" +
            "用错会静默产出无意义输出。请用 tools/deploy_qwen35.py 成对部署。"
    }

    /**
     * 兜底模型名，仅在没有实际加载信息时使用。
     *
     * 不要直接拿它当"当前模型名"用：它写死为 4B，而候选链会自动回落到 2B，
     * 于是日志里会出现"model=qwen3.5-4b"却明明加载的是 2B 的矛盾记录。
     * 请用 [displayName] 从实际选中的文件名推导。
     */
    const val MODEL_NAME = "qwen3.5-4b"
    const val MODEL_VERSION = "gguf"

    /**
     * 从实际加载的文件名推导对外展示的模型名。
     *
     * `Qwen3.5-2B-Q4_0.gguf` -> `qwen3.5-2b-q4_0`
     *
     * 存在的理由：`MODEL_NAME` 曾写死为 `qwen3.5-4b`，但设备上部署的是 2B，
     * 基准与报告因此长期把 2B 的结果标成 4B。模型身份是会写进结论的数据，
     * 不能靠常量猜。
     */
    fun displayName(fileName: String): String =
        fileName.removeSuffix(".gguf").lowercase()

    private const val TAG = "QwenModel"
    private const val ASSET_DIR = "models"
    private const val COPY_BUFFER_SIZE = 1 shl 20

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    /** Absolute path the native loader will open for the main model. */
    fun modelPath(context: Context): String = File(modelsDir(context), MODEL_FILE_NAME).absolutePath

    /** Absolute path the native loader will open for the vision projector. */
    fun mmProjPath(context: Context): String = File(modelsDir(context), MMPROJ_FILE_NAME).absolutePath

    fun isBundled(context: Context, fileName: String): Boolean = try {
        context.assets.open("$ASSET_DIR/$fileName").close()
        true
    } catch (e: Exception) {
        false
    }

    fun isInstalled(context: Context, fileName: String): Boolean =
        File(modelsDir(context), fileName).let { it.isFile && it.length() > 0 }

    /** 已安装或已打包的主模型候选，按优先级返回第一个可用的文件名。 */
    fun preferredModelFileName(context: Context): String? =
        MODEL_FILE_CANDIDATES.firstOrNull { isInstalled(context, it) || isBundled(context, it) }

    /**
     * Resolve a usable path for [fileName], copying it out of the APK on first use.
     *
     * @return the absolute path, or null when the file is neither installed nor bundled.
     */
    suspend fun ensureAvailable(context: Context, fileName: String): String? {
        val target = File(modelsDir(context), fileName)
        if (target.isFile && target.length() > 0) return target.absolutePath

        if (!isBundled(context, fileName)) {
            Log.w(TAG, "模型文件既不在私有目录也不在 assets 中: $fileName")
            return null
        }

        return withContext(Dispatchers.IO) {
            val tmp = File(target.parentFile, "$fileName.part")
            try {
                Log.i(TAG, "首次使用，从 APK 复制模型: $fileName")
                context.assets.open("$ASSET_DIR/$fileName").use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                        }
                        output.fd.sync()
                        Log.i(TAG, "复制完成: $fileName (${copied / (1024 * 1024)} MiB)")
                    }
                }
                // Publish atomically so a killed process never leaves a half file
                // that the native loader would happily mmap.
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                target.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "复制模型失败: $fileName", e)
                tmp.delete()
                null
            }
        }
    }

    /**
     * 解析首选主模型，返回 (文件名, 绝对路径)。
     *
     * 选择逻辑集中在这里，避免调用方各自拼路径 —— 之前 assets 里叫
     * `Qwen3.5-2B-UD-Q2_K_XL.gguf` 而代码里找 `Qwen3.5-2B_Q4_K_M.gguf`，
     * 导致推理从未真正启动。
     */
    suspend fun ensurePreferredModel(context: Context): Pair<String, String>? {
        for (name in MODEL_FILE_CANDIDATES) {
            val path = ensureAvailable(context, name)
            if (path != null) return name to path
        }
        return null
    }
}
