package com.example.isip

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Qualcomm GenieX NPU bring-up harness.
 *
 * Purpose: answer one question with evidence — can a normal, non-root third-party
 * app actually create a Hexagon HTP session and run a GGUF model on the NPU on
 * this device? Our own ggml-hexagon build worked from a shell CLI but failed
 * inside the app with `AEE_EUNABLETOLOAD (0x80000406)`, because the FastRPC
 * daemon resolves the DSP skel by file name on `ADSP_LIBRARY_PATH`, which an app
 * cannot set. GenieX ships the same backend plus Qualcomm's own HTP runtime, so
 * if anyone has solved that packaging problem, it is them.
 *
 * Launch with:
 *
 * ```
 * adb shell am start -n com.example.isip/.GenieXNpuTestActivity \
 *     --es backend NPU --ei maxTokens 64
 * ```
 *
 * `--es backend` accepts `NPU` / `CPU` / `GPU` / `HYBRID`. Results are appended to
 * `files/bench/geniex.txt` (this device does not surface app logs on logcat) and
 * are readable with:
 *
 * ```
 * adb shell run-as com.example.isip cat files/bench/geniex.txt
 * ```
 */
class GenieXNpuTestActivity : ComponentActivity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Same reasoning as MultimodalTestActivity: this device freezes backgrounded
        // processes aggressively (MIUI), and a frozen process reports 0.0% CPU and
        // looks exactly like a hang. Keep the screen on for the whole run.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusView = TextView(this).apply {
            setPadding(32, 48, 32, 32)
            textSize = 13f
        }
        setContentView(statusView)

        val backend = (intent.getStringExtra("backend") ?: "NPU").uppercase()
        val maxTokens = intent.getIntExtra("maxTokens", 64)
        // -1 = offload every layer (what the NPU path wants by default). A smaller
        // value is the bisection knob: the Hexagon backend aborts at the first graph
        // compute when some op in the offloaded subgraph is unsupported on the DSP,
        // and the layer count is what selects the subgraph.
        val gpuLayers = intent.getIntExtra("gpuLayers", -1)
        val prompt = intent.getStringExtra("prompt")
            ?: "Describe what a photo of a cat on a sofa would contain."

        // Deliberately NOT lifecycleScope.
        //
        // A first attempt used lifecycleScope and the run died with
        // `JobCancellationException: Job was cancelled` while staging the model:
        // this device tears the Activity down (MIUI background policy), and
        // lifecycleScope cancels with it, so a multi-minute load only ever
        // completed if the screen happened to stay on. The run is genuinely
        // process-scoped work whose output is a log file, so it gets a scope that
        // outlives the Activity and only the UI updates are guarded.
        appScope.launch { run(backend, maxTokens, gpuLayers, prompt) }
    }

    private suspend fun run(backend: String, maxTokens: Int, gpuLayers: Int, prompt: String) {
        benchFile.parentFile?.mkdirs()
        bench("")
        bench("================================================")
        bench("GenieX run @ ${System.currentTimeMillis()}")
        bench("backend=$backend maxTokens=$maxTokens gpuLayers=$gpuLayers")
        bench("filesDir=${filesDir.absolutePath}")
        bench("nativeLibraryDir=${applicationInfo.nativeLibraryDir}")
        bench("ABI=${android.os.Build.SUPPORTED_ABIS.joinToString()}")

        // ---------------------------------------------------------------
        // 1. SDK init (idempotent)
        // ---------------------------------------------------------------
        setStatus("init SDK…")
        val initOk = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine<Boolean> { cont ->
                GenieXSdk.getInstance().init(applicationContext,
                    object : GenieXSdk.InitCallback {
                        override fun onSuccess() { cont.resumeWith(Result.success(true)) }
                        override fun onFailure(message: String) {
                            bench("GenieXSdk.init onFailure: $message")
                            cont.resumeWith(Result.success(false))
                        }
                    })
            }
        }
        bench("GenieXSdk.init -> $initOk")
        if (!initOk) { statusView.text = "init failed"; return }

        // ---------------------------------------------------------------
        // 2. Import the local GGUF through the model manager.
        //
        // The model manager wants a directory whose GGUF layout it can infer, so
        // stage a clean one holding only the text model. Importing is a no-network
        // local-filesystem operation, so no chipset is required for this step.
        // ---------------------------------------------------------------
        val src = File(filesDir, "models/Qwen3.5-2B-Q4_0.gguf")
        if (!src.isFile) {
            bench("missing source model: ${src.absolutePath}")
            setStatus("missing model")
            return
        }
        val stage = File(filesDir, "geniex-models")
        stage.mkdirs()
        val staged = File(stage, src.name)
        if (!staged.isFile || staged.length() != src.length()) {
            bench("staging ${src.name} -> ${stage.absolutePath}")
            setStatus("staging model…")
            withContext(Dispatchers.IO) { src.copyTo(staged, overwrite = true) }
        }
        bench("staged model: ${staged.absolutePath} (${staged.length()} bytes)")

        val cacheKey = "local/qwen3.5-2b-q4_0"
        val cached = withContext(Dispatchers.IO) { ModelManagerWrapper.list() }.orEmpty()
        val alreadyImported = cached.any { it == cacheKey }
        bench("model cache: $cached  -> already imported: $alreadyImported")

        if (!alreadyImported) {
            setStatus("importing model…")
            withContext(Dispatchers.IO) {
                ModelManagerWrapper.pullFlow(
                    ModelPullInput(
                        model_name = cacheKey,
                        hub = HubSource.LOCALFS,
                        local_path = stage.absolutePath,
                    )
                ).collect { ev ->
                    when (ev) {
                        is ModelManagerWrapper.PullEvent.Progress ->
                            bench("  pull progress: ${ev.files}")
                        is ModelManagerWrapper.PullEvent.Completed ->
                            bench("  pull completed")
                        is ModelManagerWrapper.PullEvent.Error ->
                            bench("  pull ERROR code=${ev.code} msg=${ev.message}")
                    }
                }
            }
            bench("after import, cache=${withContext(Dispatchers.IO) { ModelManagerWrapper.list() }}")
        }

        // ---------------------------------------------------------------
        // 3. Resolve paths + build the LLM
        // ---------------------------------------------------------------
        val paths = withContext(Dispatchers.IO) { ModelManagerWrapper.getPaths(cacheKey) }
        bench("getPaths -> $paths")
        if (paths == null) {
            bench("getPaths returned null; import failed")
            setStatus("import failed")
            return
        }
        bench("  model_path = ${paths.model_path}")
        bench("  runtime_id = ${paths.runtime_id}")
        bench("  model_type = ${paths.model_type}")

        val computeUnit = when (backend) {
            "CPU" -> "cpu"
            "GPU" -> "gpu"
            "HYBRID" -> "hybrid"
            else -> "npu"
        }

        // nCtx must stay 0 for the AI Engine Direct runtime, and this model was
        // built for a 1024-token window; 1024 is a safe value for llama.cpp too.
        // nGpuLayers stays -1 = offload every layer, which is what the NPU path wants.
        //
        // Note on naming: GenieX is a Kotlin library whose properties are declared
        // `nCtx` / `nGpuLayers`. They surface here as those exact names — Kotlin
        // turns `getNCtx()` into the property `nCtx`, not `NCtx`, so the JVM-style
        // `getNCtx()` / `setNCtx(...)` spellings do not resolve.
        val cfg = ModelConfig()
        cfg.nCtx = 1024
        cfg.nGpuLayers = gpuLayers

        setStatus("loading model ($backend)…")
        bench("building LlmWrapper: compute_unit=$computeUnit nCtx=${cfg.nCtx} nGpuLayers=${cfg.nGpuLayers}")
        val loadStart = System.currentTimeMillis()
        val built = withContext(Dispatchers.IO) {
            LlmWrapper.builder()
                .llmCreateInput(
                    LlmCreateInput(
                        model_path = paths.model_path,
                        config = cfg,
                        runtime_id = paths.runtime_id.ifBlank { "llama_cpp" },
                        compute_unit = computeUnit,
                    )
                )
                .build()
        }
        val loadMs = System.currentTimeMillis() - loadStart
        bench("LlmWrapper.build -> ${if (built.isSuccess) "SUCCESS" else "FAILURE"} in ${loadMs} ms")
        built.exceptionOrNull()?.let {
            bench("  error: ${it.javaClass.name}: ${it.message}")
            it.stackTrace.take(12).forEach { l -> bench("    at $l") }
        }

        val llm = built.getOrNull()
        if (llm == null) {
            bench("RESULT backend=$backend load=FAILED loadMs=$loadMs")
            setStatus("load failed — see geniex.txt")
            return
        }

        // ---------------------------------------------------------------
        // 4. Generate
        // ---------------------------------------------------------------
        val templated = llm.applyChatTemplate(
            arrayOf(ChatMessage("user", prompt)), null, false
        )
        val formatted = templated.getOrNull()?.formattedText
        bench("applyChatTemplate -> ${if (formatted != null) "ok (${formatted.length} chars)" else "FAILED ${templated.exceptionOrNull()}"}")
        if (formatted == null) {
            bench("RESULT backend=$backend load=OK template=FAILED")
            setStatus("template failed")
            llm.close()
            return
        }
        bench("prompt: ${formatted.replace("\n", "\\n")}")

        setStatus("generating ($backend)…")
        val t0 = System.currentTimeMillis()
        var firstTokenMs = -1L
        var tokens = 0
        val sb = StringBuilder()

        llm.generateStreamFlow(formatted, GenerationConfig(maxTokens = maxTokens))
            .collect { r ->
                when (r) {
                    is LlmStreamResult.Token -> {
                        if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - t0
                        tokens++
                        sb.append(r.text)
                    }
                    is LlmStreamResult.Completed -> {
                        val total = System.currentTimeMillis() - t0
                        val p = r.profile
                        // NOTE: the whole concatenation must be wrapped before
                        // .format(); `"a" + "b".format(x)` formats only "b".
                        bench(
                            (
                                "profile: ttftMs=%.1f promptTokens=%d prefillTokPerSec=%.2f " +
                                    "generatedTokens=%d decodingTokPerSec=%.2f promptTimeMs=%.1f " +
                                    "decodeTimeMs=%.1f stop=%s"
                                ).format(
                                p.ttftMs, p.promptTokens, p.prefillSpeed,
                                p.generatedTokens, p.decodingSpeed, p.promptTimeMs,
                                p.decodeTimeMs, p.stopReason
                            )
                        )
                        bench("timing: ttft=${firstTokenMs}ms total=${total}ms tokens=$tokens")
                    }
                    is LlmStreamResult.Error -> {
                        bench("stream ERROR: ${r.throwable.javaClass.name}: ${r.throwable.message}")
                        r.throwable.stackTrace.take(12).forEach { l -> bench("    at $l") }
                    }
                }
            }

        val totalMs = System.currentTimeMillis() - t0
        val decodeMs = if (firstTokenMs > 0) totalMs - firstTokenMs else totalMs
        val decodeTps = if (decodeMs > 0 && tokens > 1) (tokens - 1) * 1000.0 / decodeMs else 0.0

        bench("output: ${sb.toString().replace("\n", "\\n").take(600)}")
        bench(
            "RESULT backend=$backend load=OK loadMs=$loadMs ttftMs=$firstTokenMs " +
                "totalMs=$totalMs tokens=$tokens decodeTps=%.2f".format(decodeTps)
        )
        setStatus("done: $backend  ${"%.2f".format(decodeTps)} tok/s")

        llm.close()
    }

    /** Update the on-screen status, if the Activity is still alive to show it. */
    private fun setStatus(text: String) {
        runCatching {
            if (!isFinishing && !isDestroyed) statusView.text = text
        }
    }

    /**
     * Append one line to the bench log and echo it to logcat.
     *
     * Deliberately a plain function doing a blocking append of a few dozen bytes:
     * it is called from suspend contexts and from non-suspend callbacks alike
     * (e.g. `GenieXSdk.InitCallback.onFailure`), and at this size the write cost
     * is negligible next to the inference it is measuring.
     *
     * Logging targets the application context's files dir so that tearing down the
     * Activity cannot lose results of a run that is still in flight.
     */
    private fun bench(line: String) {
        Log.i(TAG, line)
        runCatching {
            benchFile.parentFile?.mkdirs()
            benchFile.appendText(line + "\n")
        }
    }

    private companion object {
        const val TAG = "GenieXNpuTest"

        /**
         * Process-scoped, not Activity-scoped. See the comment in [onCreate].
         */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    /** Path is derived from the application context, for the same reason. */
    private val benchFile: File
        get() = File(applicationContext.filesDir, "bench/geniex.txt")
}
