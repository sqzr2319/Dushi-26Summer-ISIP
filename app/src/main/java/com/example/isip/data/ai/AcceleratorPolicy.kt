package com.example.isip.data.ai

import android.content.Context
import android.util.Log

/**
 * 决定是否尝试 Hexagon NPU，并在失败时自动降级 —— 且这个决定跨进程重启保留。
 *
 * ## 为什么需要它
 *
 * NPU 在本机实测有效（文本约 1.5–1.9×、多模态约 1.2–1.35×，见
 * `doc/npu-hexagon-inapp.md`），所以相册分析应当默认吃到它。但这条路径有一个
 * 已经实测过的失效模式，必须防住：
 *
 * 会话创建失败时（修复前是 `AEE_EUNABLETOLOAD 0x80000406`），ggml 注册表里会留下
 * 一个**没有任何可用设备**的 HTP 条目。llama.cpp 的调度器在枚举设备时会卡死，
 * 于是**连纯 CPU 推理都跑不动** —— 即"失败的 NPU 集成会破坏核心功能"。
 *
 * 因此这里不假设 NPU 一定可用，而是：默认尝试 + 连续失败即持久化禁用 + 可从设置
 * 页重新启用。失败计数写在 SharedPreferences 里，所以即使某次尝试以进程被杀结束，
 * 下次启动也会直接跳过 NPU，不会反复卡在同一个坑里。
 *
 * ## 为什么不做"运行时探测"
 *
 * 探测 NPU 是否可用唯一可靠的方式是**真的创建一次会话**，而这正是可能卡死的那一步 ——
 * 在一个可能永久阻塞的调用上加超时是不可靠的。所以这里选择更保守也更简单的策略：
 * 用真实推理的结果记账，失败到阈值就退出，把决策权交还给用户。
 */
class AcceleratorPolicy(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    /** 是否允许尝试 NPU。 */
    fun isNpuEnabled(): Boolean = prefs.getBoolean(KEY_NPU_ENABLED, true)

    /** 连续失败次数。 */
    fun failureCount(): Int = prefs.getInt(KEY_NPU_FAILURES, 0)

    /**
     * 是否应当在此次加载中启用 NPU 后端。
     *
     * 只有在用户没关掉、且连续失败未达阈值时才返回 true。
     */
    fun shouldAttemptNpu(): Boolean = isNpuEnabled() && failureCount() < FAILURE_THRESHOLD

    /** 上一次尝试失败的人话原因，用于在设置页如实展示。 */
    fun lastFailureReason(): String? = prefs.getString(KEY_NPU_LAST_REASON, null)

    /** NPU 推理成功，清零失败计数。 */
    fun recordNpuSuccess() {
        if (failureCount() != 0) {
            Log.i(TAG, "NPU 推理成功，失败计数清零（原为 ${failureCount()}）")
        }
        prefs.edit()
            .putInt(KEY_NPU_FAILURES, 0)
            .remove(KEY_NPU_LAST_REASON)
            .apply()
    }

    /**
     * NPU 推理失败。
     *
     * 达到阈值后 [shouldAttemptNpu] 会返回 false，即自动降级到 CPU，
     * 无需重启进程，也不需要用户干预。
     */
    fun recordNpuFailure(reason: String) {
        val n = failureCount() + 1
        Log.w(TAG, "NPU 推理失败（第 $n 次，阈值 $FAILURE_THRESHOLD）: $reason")
        prefs.edit()
            .putInt(KEY_NPU_FAILURES, n)
            .putString(KEY_NPU_LAST_REASON, reason)
            .apply()
        if (n >= FAILURE_THRESHOLD) {
            Log.w(TAG, "NPU 连续失败达阈值，已自动降级到 CPU。可在设置页重新启用。")
        }
    }

    /** 用户在设置页手动复位：清掉失败计数并重新允许 NPU。 */
    fun resetNpu() {
        Log.i(TAG, "用户手动重置 NPU 状态")
        prefs.edit()
            .putBoolean(KEY_NPU_ENABLED, true)
            .putInt(KEY_NPU_FAILURES, 0)
            .remove(KEY_NPU_LAST_REASON)
            .apply()
    }

    /** 用户在设置页关闭 NPU。 */
    fun disableNpu() {
        prefs.edit().putBoolean(KEY_NPU_ENABLED, false).apply()
    }

    // ----------------------------------------------------------------------
    // 跨进程死亡的崩溃检测
    // ----------------------------------------------------------------------
    //
    // NPU 路径的两种失效模式，处理方式必须不同：
    //
    //   1. 加载期失败（注册表里出现无设备的 HTP 条目）—— 可被 Kotlin 捕获，
    //      用 recordNpuFailure() 直接记账即可。
    //   2. 推理期崩溃（SIGABRT / 进程被杀）—— catch 不到，进程直接没了。
    //
    // 第 2 种只能靠"留下的痕迹"反推：推理开始前置位，正常结束后清位。若下次启动
    // 发现标记还在，说明上一次推理没有正常结束。标记写在 SharedPreferences 里，
    // 所以能跨越进程死亡存活。

    /** 在开始一次 NPU 推理前置位。 */
    fun markInferenceStarted() {
        prefs.edit().putBoolean(KEY_INFERENCE_IN_FLIGHT, true).apply()
    }

    /** 推理正常结束后清位。 */
    fun markInferenceCompleted() {
        prefs.edit().putBoolean(KEY_INFERENCE_IN_FLIGHT, false).apply()
    }

    /**
     * 检查上一次 NPU 推理是否异常终止，并据此记账。
     *
     * 应在后端加载之前调用一次。返回 true 表示检测到上次崩溃。
     */
    fun checkForPreviousCrash(): Boolean {
        if (!prefs.getBoolean(KEY_INFERENCE_IN_FLIGHT, false)) return false
        markInferenceCompleted()
        recordNpuFailure("上一次 NPU 推理异常终止（进程死亡，未走完正常结束路径）")
        Log.w(TAG, "检测到上次 NPU 推理异常终止，已记账并降级")
        return true
    }

    /**
     * 当前实际会用的加速级别，用于 UI 如实展示。
     *
     * 注意这是**策略层面**的判断；真正生效的后端要看
     * [LlamaCppWrapper.describeBackends] 的原生报告 —— llama.cpp 在加速后端缺失时
     * 会静默回退到 CPU，所以两者都要看。
     *
     * 文案里是"CPU"而不是"GPU/CPU"：`AUTO` 的回落链已把 Vulkan 移除，因为它在这台
     * 机器上会算出乱码（见 [AccelerationBackend.GPU_VULKAN]）。UI 若还写"回落 GPU"
     * 就是在承诺一件不会发生、也不该发生的事。
     */
    fun describe(): String = when {
        !isNpuEnabled() -> "CPU（NPU 已被用户关闭）"
        failureCount() >= FAILURE_THRESHOLD ->
            "CPU（NPU 连续失败 ${failureCount()} 次后自动降级）"
        else -> "NPU 优先（失败自动回落 CPU）"
    }

    companion object {
        private const val TAG = "AcceleratorPolicy"
        private const val PREFERENCES_NAME = "isip_accelerator"
        private const val KEY_NPU_ENABLED = "npu_enabled"
        private const val KEY_NPU_FAILURES = "npu_failures"
        private const val KEY_NPU_LAST_REASON = "npu_last_reason"
        private const val KEY_INFERENCE_IN_FLIGHT = "npu_inference_in_flight"

        /**
         * 连续失败多少次后放弃 NPU。
         *
         * 取 2 而不是 1：单次失败可能是内存压力、热限制等偶发原因；连续两次则说明
         * 这台设备/这个模型组合确实不行，此时继续尝试只会让每次启动都面临卡死风险。
         */
        const val FAILURE_THRESHOLD = 2
    }
}
