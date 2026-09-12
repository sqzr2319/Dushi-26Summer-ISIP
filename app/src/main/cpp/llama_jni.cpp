#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <cstdio>
#include <cstdarg>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <ctime>
#include <mutex>
#include <unistd.h>
#include <sys/stat.h>
#include <android/log.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"
#include "ggml-backend.h"
#ifdef GGML_USE_VULKAN
#include "ggml-vulkan.h"
#endif

#define LOG_TAG "LlamaJNI"
#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); jni_log_line("I", __VA_ARGS__); } while (0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); jni_log_line("E", __VA_ARGS__); } while (0)
#define LOGW(...) do { __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__); jni_log_line("W", __VA_ARGS__); } while (0)

// ---------------------------------------------------------------------------
// 把 UTF-8 文本安全地交给 Java
// ---------------------------------------------------------------------------
// **不要用 env->NewStringUTF() 传模型输出。**
//
// NewStringUTF 要求的是 *Modified UTF-8*（CESU-8），不是标准 UTF-8：它既不接受
// 4 字节序列（U+10000 以上，也就是 emoji），也不接受任何非法字节。模型输出无法
// 保证这两点，于是 CheckJNI 会直接 abort 整个进程：
//
//   JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8:
//     illegal continuation byte 0x20
//     in call to NewStringUTF
//     from ...LlamaCppNative.nativeGenerateMultimodal
//
// 这个崩溃此前一直没暴露，因为基准用的合成图让模型只输出 ASCII；一换到真实照片
// 分析、模型开始输出 emoji 就必崩。
//
// 正确做法是走 NewString（接收 UTF-16）：UTF-8 → UTF-16 的转换本身就能处理 4 字节
// 序列，遇到真正非法的字节再用 U+FFFD 替换，绝不抛错。
//
// 静态 ASCII 字面量（如 "{\"error\":...}"）仍可直接用 NewStringUTF，它们本来就是
// Modified UTF-8 的子集。
static jstring utf8_to_jstring(JNIEnv* env, const char* s) {
    if (s == nullptr) return env->NewStringUTF("");

    const auto* p = reinterpret_cast<const unsigned char*>(s);
    std::vector<jchar> out;
    out.reserve(strlen(s));

    while (*p != 0) {
        uint32_t cp = 0;
        int extra = 0;

        if (*p < 0x80) {
            cp = *p;
        } else if ((*p & 0xE0) == 0xC0) {
            cp = *p & 0x1Fu; extra = 1;
        } else if ((*p & 0xF0) == 0xE0) {
            cp = *p & 0x0Fu; extra = 2;
        } else if ((*p & 0xF8) == 0xF0) {
            cp = *p & 0x07u; extra = 3;
        } else {
            // 非法起始字节（含 0x80-0xBF 的孤立续接字节）
            out.push_back(0xFFFD);
            ++p;
            continue;
        }
        ++p;

        bool bad = false;
        for (int i = 0; i < extra; ++i) {
            if ((*p & 0xC0) != 0x80) { bad = true; break; }
            cp = (cp << 6) | (*p & 0x3Fu);
            ++p;
        }
        if (bad) {
            out.push_back(0xFFFD);
            continue;
        }

        // 拒绝过长编码与代理区码点，避免产出无法表示的 UTF-16
        const bool overlong =
            (extra == 1 && cp < 0x80) || (extra == 2 && cp < 0x800) ||
            (extra == 3 && cp < 0x10000);
        if (overlong || (cp >= 0xD800 && cp <= 0xDFFF) || cp > 0x10FFFF) {
            out.push_back(0xFFFD);
            continue;
        }

        if (cp <= 0xFFFF) {
            out.push_back(static_cast<jchar>(cp));
        } else {
            // 需要代理对
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }

    jstring result = env->NewString(out.data(), static_cast<jsize>(out.size()));
    return result != nullptr ? result : env->NewStringUTF("");
}

// ---------------------------------------------------------------------------
// 文件日志
//
// 这台设备（Android 16 / MIUI）不会把应用自身的 logcat 输出交给 adb，只靠
// logcat 无法调试原生层的挂死点。所以同一条日志再写一份到
// /data/data/<pkg>/files/bench/native.log，取法：
//   adb shell run-as com.example.isip cat files/bench/native.log
//
// 目的是定位"卡在哪一步"：日志按阶段打点，进程被强杀后仍能看到最后一步。
// ---------------------------------------------------------------------------
static std::string g_log_path;              // 由 nativeSetLogDir 设置
static std::mutex g_log_mutex;
static int g_log_append_count = 0;

// 日志文件大小上限。超过就重命名为 .1 并重新开始。
//
// 为什么必须有：这条文件日志在**生产路径**上也是开着的（LlamaCppWrapper.loadModel
// 会自动调用 enableNativeFileLog），而相册分析是一个几千张照片的批量任务。实测单次
// 推理写 2-5 KB，6,848 张照片就是几十 MB，且只增不减 —— 用户手机上不该出现这种文件。
//
// 保留 .1 而不是直接删：出问题时上一次的日志往往才是有用的那次。
static const long g_log_max_bytes = 8 * 1024 * 1024;

static void jni_log_line(const char * level, const char * fmt, ...) {
    if (g_log_path.empty()) return;
    char msg[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);

    std::lock_guard<std::mutex> lock(g_log_mutex);

    // 每 512 行才 stat 一次：每行都 stat 会让日志本身成为可观开销
    if ((++g_log_append_count & 511) == 0) {
        struct stat st;
        if (stat(g_log_path.c_str(), &st) == 0 && st.st_size > g_log_max_bytes) {
            std::string rotated = g_log_path + ".1";
            remove(rotated.c_str());
            rename(g_log_path.c_str(), rotated.c_str());
        }
    }

    FILE * f = fopen(g_log_path.c_str(), "a");
    if (f == nullptr) return;
    time_t now = time(nullptr);
    struct tm tmv;
    localtime_r(&now, &tmv);
    fprintf(f, "%02d:%02d:%02d [%s] %s\n",
            tmv.tm_hour, tmv.tm_min, tmv.tm_sec, level, msg);
    fclose(f);
}

// ---------------------------------------------------------------------------
// 加速后端探测
//
// llama.cpp 的行为是：当没有任何加速后端能接管层时，它**静默**留在 CPU，
// 不报错。所以 n_gpu_layers>0 完全不能证明 GPU 生效了 —— 必须回过头问 ggml
// 注册表：到底注册了哪些后端、各自看到几个设备、显存多大。
//
// 这里对任何后端都成立（Vulkan / Hexagon NPU / CPU ...），不绑定某一个，
// 这样后续接 NPU 时同一套日志可以直接复用来判断走的是哪条路。
// ---------------------------------------------------------------------------
static std::string describe_backends() {
    std::ostringstream out;

    const size_t n_reg = ggml_backend_reg_count();
    if (n_reg == 0) {
        return "no ggml backend registered";
    }

    for (size_t i = 0; i < n_reg; ++i) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        if (reg == nullptr) continue;

        const char * name = ggml_backend_reg_name(reg);
        const size_t n_dev = ggml_backend_reg_dev_count(reg);

        if (out.tellp() > 0) out << "; ";
        out << (name ? name : "?") << ": " << n_dev << " device(s)";

        if (n_dev > 0) {
            out << " [";
            for (size_t d = 0; d < n_dev; ++d) {
                ggml_backend_dev_t dev = ggml_backend_reg_dev_get(reg, d);
                if (dev == nullptr) continue;
                if (d > 0) out << ", ";
                out << ggml_backend_dev_name(dev);
                size_t free_mem = 0, total_mem = 0;
                ggml_backend_dev_memory(dev, &free_mem, &total_mem);
                if (total_mem > 0) {
                    out << " " << (total_mem / (1024 * 1024)) << "MiB";
                }
            }
            out << "]";
        }
    }
    return out.str();
}

// 从模型输出里剥掉思维链，只保留真正的回答。
//
// **这里曾经有一处会把答案整段删掉的 bug。** 原实现是：
//
//     size_t sp = result.find("<think>");
//     size_t ep = result.find("</think>");
//     if (sp != npos && ep != npos) result.erase(sp, ep + 8 - sp);
//
// `erase(pos, len)` 删掉的是**从 pos 开始往后 len 个字符**，所以它连
// `</think>` 之后的整个答案一起删了。实测模型输出：
//
//     <think>\n\n</think>\n\n图中是蓝天、绿地，和太阳。
//
// 剥掉之后只剩空串，多模态"描述"因此一直是空的 —— 而 bench 侧看到 chars=12
// （UTF-8 字节数）还以为是乱码或模型退化。真相是模型答得完全正确，是这里把答案
// 擦掉了。不要在再犯第二遍：`</think>` 之后才是答案，要保留的是它。
//
// 幂等：不含 `</think>` 的文本原样返回；以 `<think>` 开头但没闭合的（被 maxTokens
// 截断）整段丢弃，避免把半截思维链当答案。
static std::string strip_think_block(const std::string& text) {
    static const std::string kOpen  = "<think>";
    static const std::string kClose = "</think>";

    const size_t open  = text.find(kOpen);
    const size_t close = text.find(kClose);

    if (close != std::string::npos) {
        size_t start = close + kClose.size();
        while (start < text.size() && (text[start] == '\n' || text[start] == '\r' || text[start] == ' ')) {
            ++start;
        }
        return text.substr(start);
    }
    if (open != std::string::npos) {
        // 有开头没结尾：说明思维链被截断，还没有产生答案
        return std::string();
    }
    return text;
}

// 从 JPEG 字节流里读出宽高。
//
// 为什么需要：图像 token 数由送入图片的尺寸决定（约 (边长/patch/merge)^2 * 比例），
// 而 Kotlin 侧的日志在这台设备上拿不到（App 自身 logcat 不交给 adb，且分析若挂死
// 就永远写不到结果文件）。在原生侧直接解析 JPEG 头，是唯一能确认"到底送了多大图"
// 的可靠途径。只走 SOF 段，不做完整解码。
static bool jpeg_dimensions(const unsigned char* data, size_t len, int* out_w, int* out_h) {
    if (data == nullptr || len < 4) return false;
    if (data[0] != 0xFF || data[1] != 0xD8) return false;   // SOI

    size_t i = 2;
    while (i + 9 < len) {
        if (data[i] != 0xFF) { ++i; continue; }
        const unsigned char marker = data[i + 1];
        i += 2;
        if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) continue;
        if (i + 1 >= len) break;
        const size_t seg_len = ((size_t)data[i] << 8) | data[i + 1];

        // SOF0..SOF3, SOF5..SOF7, SOF9..SOF11, SOF13..SOF15 carry the frame size.
        const bool is_sof = (marker >= 0xC0 && marker <= 0xCF) &&
                            marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
        if (is_sof && i + 7 < len) {
            *out_h = ((int)data[i + 3] << 8) | data[i + 4];
            *out_w = ((int)data[i + 5] << 8) | data[i + 6];
            return true;
        }
        i += seg_len;
    }
    return false;
}

// llama.cpp 日志回调 —— 将内部日志重定向到 Android logcat 和文件日志。
//
// 注意这里**不能**按级别过滤：后端模块加载失败的原因走的是 GGML_LOG_DEBUG
// （见 ggml-backend-reg.cpp 的 load_backend 失败分支），把 DEBUG 丢掉就等于把
// 唯一的诊断线索丢掉。文件日志由 benchLog 之外的原生侧写入，成本可接受。
static void llama_log_to_logcat(ggml_log_level level, const char * text, void * /* user_data */) {
    // 去掉末尾换行符
    std::string msg(text);
    while (!msg.empty() && (msg.back() == '\n' || msg.back() == '\r')) {
        msg.pop_back();
    }
    if (msg.empty()) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: __android_log_print(ANDROID_LOG_ERROR, "llama.cpp", "%s", msg.c_str()); break;
        case GGML_LOG_LEVEL_WARN:  __android_log_print(ANDROID_LOG_WARN,  "llama.cpp", "%s", msg.c_str()); break;
        case GGML_LOG_LEVEL_INFO:  __android_log_print(ANDROID_LOG_INFO,  "llama.cpp", "%s", msg.c_str()); break;
        default:                   __android_log_print(ANDROID_LOG_DEBUG, "llama.cpp", "%s", msg.c_str()); break;
    }
    // 同样落到文件，否则在拿不到 logcat 的设备上这些线索就丢了
    jni_log_line("L", "%s", msg.c_str());
}

// 封装 llama.cpp 实例
struct LlamaInstance {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    const llama_vocab* vocab = nullptr;
};

// 泛型采样器链：包含 temperature 与 greedy，因此同一个 sampler 可以在不同温度下
// 复用 —— 之前 temp 在 init 时固定、后续 temperature 参数被彻底忽略。
//
// penalties 是保留项，但它的**归因不确定**，不要当成已验证的修复。
//
// 起因：曾在生产路径（长中文 prompt + 512 token 预算）观察到输出退化成多语言乱码
// （"研制هما磁场an.vocab dueisanobili..."），当时判定为"重复/退化采样，缺惩罚"。
//
// 但后续会话发现，**完全相同的症状**其实是 Vulkan 后端算错的特征（见文件末尾
// accelMode=0 分支的注释），而当时那些运行恰好走的正是 AUTO -> Vulkan。
// 所以"加惩罚修好了"这个因果链从未被单独验证过 —— 二者都被改动了。
//
// 保留的理由：top_k + 存在性惩罚对结构化输出是常规做法，实测不损害正确输出，
// 且能降低采样跑偏的概率。若要重新验证，请在 CPU/NPU 上做有/无惩罚的对照。
static llama_sampler* make_sampler(float temperature, int32_t seed) {
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = false;
    llama_sampler* chain = llama_sampler_chain_init(sparams);

    // 顺序遵循 llama.h 的建议：先按 top-k 收窄候选，再做重复惩罚（在全词表上做
    // 惩罚会慢），最后才是温度与随机采样。
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));

    // last_n=64 覆盖约一到两句话的跨度；1.1 是 llama.cpp 的常用默认强度。
    // freq/present 保持 0（禁用），只做存在性惩罚，避免过度压制正常重复用词。
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(64, 1.1f, 0.0f, 0.0f));

    if (temperature > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    } else {
        // temperature <= 0 视为贪心，保证可复现
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    }
    return chain;
}

// 把上下文恢复到"可以开始一段全新对话"的状态。
//
// 多模态推理必须调用：mtmd 每次都从位置 0 重新写入图像与文本 token，如果上一轮
// 的 KV 还在，第二次 llama_decode 就会因为位置冲突失败（实测表现为第二次起立刻
// 返回 {"error":"Evaluation failed"}）。采样器链也要清空，否则上一轮的状态会
// 渗进下一轮。
//
// 只清 KV 不够：还需要清空元数据并把位置计数归零。否则下一轮 prefill 会被报
// "non-consecutive token position N after M"，llama.cpp 只能反复做上下文移位，
// 实测把多模态耗时从 13.4s 拖到 23.8s。
static void reset_context(LlamaInstance* instance, float temperature) {
    llama_memory_t mem = llama_get_memory(instance->ctx);
    if (mem != nullptr) {
        // 先按序列移除，再整体清空。
        // 序列级移除会把该序列的位置簿记一起复位；只做 clear 时实测下一轮 prefill 仍报
        // "non-consecutive token position N after M"，llama.cpp 只能反复上下文移位。
        llama_memory_seq_rm(mem, 0, -1, -1);
        // data=true：连数据缓冲一起清，避免旧 K/V 残留
        llama_memory_clear(mem, true);
    }

    if (instance->sampler) {
        llama_sampler_free(instance->sampler);
    }
    instance->sampler = make_sampler(temperature, LLAMA_DEFAULT_SEED);
}

// 多模态 (mtmd) 支持
static mtmd_context*  g_mtmd_ctx   = nullptr;
static bool           g_has_mmproj = false;
static llama_model*   g_model      = nullptr;  // 全局模型指针，供 mtmd 使用

// Flash attention 模式：0=自动（llama.cpp 默认），1=强制关闭，2=强制开启。
// 由 nativeSetFlashAttnMode 设置，在 nativeInit 建上下文时生效。
static int            g_flash_attn_mode = 0;

// nativeInit 传入的 CPU 线程数，转存给 mtmd 用。
//
// 为什么需要转存：视觉塔（mmproj）是在 nativeLoadMmproj 里建的独立 mtmd 上下文，
// 拿不到 nativeInit 的参数，于是那边一直硬编码 4 线程。而单张分析 60 秒里有约
// 34 秒花在视觉塔的 CPU 编码上 —— 也就是说主要瓶颈被钉死在一个写死的数字上，
// 且它比设备核数（SM8750 为 8 核）少一半。
static int            g_n_threads = 4;

// 视觉塔单独使用的线程数（<=0 表示跟随 g_n_threads）。
//
// 为什么值得单独控制：单张分析里最长的一段是视觉塔的 CPU 编码（768px 下约 31 秒，
// 占总时间一半），而它和 LLM 的线程数是两回事 —— 视觉塔是独立的 mtmd 上下文、
// 独立的 ggml 线程池。实测把**两者一起**提到 8 线程会在 LLM prefill 阶段挂死
// （两次复现），但这不能证明视觉塔自己不能用更多线程。
static int            g_mmproj_threads = -1;

// 视觉塔是否交给加速后端（默认 false）。见 nativeLoadMmproj 的说明。
static int            g_mmproj_use_gpu = 0;

// KV cache 的量化类型：0=默认(f16)，1=q8_0，2=q4_0。
//
// decode 约占单张分析的三分之一（约 180 token @ 12 tok/s），而 NPU 上每步都要
// 读写整个 KV cache。把它量化能同时减小内存带宽与占用，代价是精度。
static int            g_kv_quant = 0;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeInit(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath,
    jint nThreads,
    jint nCtx,
    jint nGpuLayers,
    jint nAccelMode
) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    LOGI("=== 初始化 llama.cpp ===");
    LOGI("模型: %s", path);
    LOGI("线程: %d, 上下文: %d, GPU层: %d, 加速模式: %d", nThreads, nCtx, nGpuLayers, nAccelMode);

    // 视觉塔的线程数跟随主配置。见 g_n_threads 的说明：这个数字此前被钉死在 4，
    // 而它决定了单张分析里最长那一段（视觉塔 CPU 编码）的耗时。
    g_n_threads = (nThreads > 0) ? nThreads : 4;

    // 将 llama.cpp 内部日志重定向到 Android logcat
    llama_log_set(llama_log_to_logcat, nullptr);
    // mtmd/clip 是独立 logger，默认写 stderr（Android 上丢弃）。见 nativeSetLogFile 的说明。
    mtmd_log_set(llama_log_to_logcat, nullptr);

    try {
        // 把已静态编译进来的后端登记到 ggml 注册表。
        // Vulkan 后端随本库静态链接（见 CMakeLists），所以不需要加载 .so；
        // ggml_backend_load_all() 只是顺带把运行时目录里的动态后端也扫一遍。
        LOGI("正在加载 ggml 后端...");
        ggml_backend_load_all();

#ifdef GGML_USE_VULKAN
        // Vulkan 后端在 ggml_backend_reg_count() 里，但注册表要到第一次被查询时
        // 才惰性初始化，所以这里显式摸一下，确保它已经就绪。
        const int vk_devices = ggml_backend_vk_get_device_count();
        LOGI("Vulkan 设备数量: %d", vk_devices);
#endif

        // 关键日志：说明加速到底有没有生效。nGpuLayers>0 只表示"想用"，
        // 这行才表示"能用"。
        LOGI("已注册后端: %s", describe_backends().c_str());
        LOGI("请求卸载层数: %d", nGpuLayers);

        // 配置模型参数
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = nGpuLayers;

        // 在已注册的后端里挑出本次真正允许使用的设备。
        //
        // 为什么必须显式指定：llama.cpp 的调度器会把 *所有* 可见加速设备都纳入
        // 张量分配，与 n_gpu_layers 无关。实测后果是，只要 Vulkan 被注册成可用设备，
        // 即使 n_gpu_layers=0 也会参与分配，纯 CPU 推理从 12.05 tok/s 掉到 5.54 tok/s，
        // 多模态从 13.4s 涨到 23.8s。
        //
        // nAccelMode: 0=自动(NPU>CPU), 1=仅 CPU, 2=Vulkan(CPU 兜底), 3=Hexagon(CPU 兜底)
        //
        // 回落语义：add()/add_by_type() 对未命中的名字是静默跳过的，所以"想要
        // HTP0 但 NPU 后端没注册成功"会自动退化成"只有 CPU"，不会留下悬空条目。
        // 这正是本项目的验收要求：NPU 优先，失败自动回落，并如实报告。
        // 每次探测都打日志，便于从 native.log 判断最终落到了哪一级。
        static ggml_backend_dev_t s_selected_devs[4];
        {
            int n = 0;
            auto add = [&](const char * name) {
                ggml_backend_dev_t d = ggml_backend_dev_by_name(name);
                if (d != nullptr && n < 3) {
                    s_selected_devs[n++] = d;
                    LOGI("  设备命中: %s", name);
                } else {
                    LOGI("  设备未命中（跳过，将回落到下一级）: %s", name);
                }
            };
            auto add_by_type = [&](enum ggml_backend_dev_type t, const char * what) {
                ggml_backend_dev_t d = ggml_backend_dev_by_type(t);
                if (d != nullptr && n < 3) {
                    s_selected_devs[n++] = d;
                    LOGI("  设备命中: %s (%s)", ggml_backend_dev_name(d), what);
                } else {
                    LOGI("  设备未命中（跳过）: %s", what);
                }
            };

            switch (nAccelMode) {
                case 1:  // 仅 CPU
                    add_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, "CPU");
                    break;
                case 2:  // Vulkan 优先，CPU 兜底
                    add("Vulkan0");
                    add_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, "CPU 兜底");
                    break;
                case 3:  // NPU 优先，CPU 兜底
                    add("HTP0");
                    add_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, "CPU 兜底");
                    break;
                default:
                    // 自动：按 "NPU > CPU" 的偏好顺序显式挑选。
                    //
                    // 不交给 llama.cpp 自己挑，原因见上面的注释：它会把所有可见
                    // 加速设备无差别纳入分配。显式排序既避免这个问题，又让
                    // "优先 NPU、其次 CPU" 这条策略在本层可读、可日志追溯。
                    //
                    // ⚠️ Vulkan 已被**移出**这条链，这是实测结论而非保守选择：
                    // 在同一张真实照片、同一提示词、同一图像 token 数（640x360 ->
                    // 220 tokens）下，CPU 稳定产出正确描述，而 Vulkan 产出乱码：
                    //
                    //   gpuLayers=0（Vulkan 已注册但 0 层卸载）-> 正确
                    //   gpuLayers=4 / 12 / 24 / 999            -> 全部乱码
                    //
                    // 且乱码并非多模态特有：纯文本 "请从1数到40" 在 CPU 上得到
                    // `1,2,3,...,14`，在 Vulkan 上得到多语言乱码。JSON 的**结构**
                    // 仍然完整（categories/tags/ocr_content 等字段都在），
                    // 说明模型在跑、只是数值错了 —— 属于后端算子正确性问题。
                    //
                    // 已排除的开关（都没有改善，部分只改变乱码内容，说明机制生效
                    // 但根因不在那一处）：GGML_VK_DISABLE_FUSION / _ASYNC / _COOPMAT /
                    // _COOPMAT2 / _MMVQ / _GRAPH_OPTIMIZE、GGML_VK_FORCE_MMVQ，
                    // 以及 flash attention 强制开/关。
                    //
                    // 结论：Vulkan 在这台 SM8750 上"能跑完但算错"，比"跑不动"更危险。
                    // 它仍可由 accelMode=2 显式指定（用于继续排查），但绝不参与自动回落，
                    // 否则生产路径会静默产出乱码 —— 这正是此前"输出不可用"的根因。
                    add("HTP0");
                    add_by_type(GGML_BACKEND_DEVICE_TYPE_CPU, "CPU 兜底");
                    break;
            }

            if (n > 0) {
                s_selected_devs[n] = nullptr;
                model_params.devices = s_selected_devs;
                LOGI("设备选择: accelMode=%d -> %d 个设备", nAccelMode, n);
                for (int i = 0; i < n; ++i) {
                    LOGI("  dev[%d] = %s", i, ggml_backend_dev_name(s_selected_devs[i]));
                }
                // 如实报告实际生效的加速级别，而不是请求的级别。
                const char * active = "CPU";
                for (int i = 0; i < n; ++i) {
                    const char * dn = ggml_backend_dev_name(s_selected_devs[i]);
                    if (dn != nullptr && std::strncmp(dn, "HTP", 3) == 0) { active = "NPU(Hexagon HTP)"; break; }
                    if (dn != nullptr && std::strncmp(dn, "Vulkan", 6) == 0) { active = "GPU(Vulkan)"; }
                }
                LOGI("实际生效加速级别: %s", active);
            } else {
                LOGW("未匹配到任何设备，交给 llama.cpp 自动选择 (accelMode=%d)", nAccelMode);
            }
        }

        // 加载模型
        LOGI("[stage] llama_model_load_from_file 开始");
        llama_model* model = llama_model_load_from_file(path, model_params);
        LOGI("[stage] llama_model_load_from_file 结束: %p", (void*)model);
        if (!model) {
            LOGE("❌ 模型加载失败");
            env->ReleaseStringUTFChars(modelPath, path);
            return 0;
        }

        // 获取词汇表
        const llama_vocab* vocab = llama_model_get_vocab(model);

        // 配置上下文参数
        llama_context_params ctx_params = llama_context_default_params();
        ctx_params.n_ctx = nCtx;
        ctx_params.n_threads = nThreads;
        ctx_params.n_batch = 128;
        ctx_params.n_ubatch = 128;
        ctx_params.no_perf = false;
        // Flash attention 开关。0=交给 llama.cpp 自动判断（默认），1=强制关闭，2=强制开启。
        //
        // 存在理由：Vulkan 上 offload 任意层（实测 4/25 层起）都会把输出变成乱码，
        // 而 0 层时完全正确 —— 说明错在 Vulkan 的算子实现，不是设备选择。FA 是
        // Vulkan 路径上嫌疑最大的一项（mtmd 那侧早已关掉 FA），必须能单独关掉验证。
        ctx_params.flash_attn_type = (g_flash_attn_mode == 1)
            ? LLAMA_FLASH_ATTN_TYPE_DISABLED
            : (g_flash_attn_mode == 2 ? LLAMA_FLASH_ATTN_TYPE_ENABLED
                                      : LLAMA_FLASH_ATTN_TYPE_AUTO);
        LOGI("flash_attn_mode=%d -> flash_attn_type=%d", g_flash_attn_mode, (int)ctx_params.flash_attn_type);

        // KV cache 量化（decode 占单张分析约三分之一，是内存带宽敏感的）。
        if (g_kv_quant == 1) {
            ctx_params.type_k = GGML_TYPE_Q8_0;
            ctx_params.type_v = GGML_TYPE_Q8_0;
        } else if (g_kv_quant == 2) {
            ctx_params.type_k = GGML_TYPE_Q4_0;
            ctx_params.type_v = GGML_TYPE_Q4_0;
        }
        if (g_kv_quant != 0) {
            LOGI("KV cache 量化: type_k=%d type_v=%d (0=f16 1=q8_0 2=q4_0)",
                 (int)ctx_params.type_k, (int)ctx_params.type_v);
        }

        // 创建上下文
        LOGI("[stage] llama_init_from_model 开始");
        llama_context* ctx = llama_init_from_model(model, ctx_params);
        LOGI("[stage] llama_init_from_model 结束: %p", (void*)ctx);
        if (!ctx) {
            LOGE("❌ 上下文创建失败");
            llama_model_free(model);
            env->ReleaseStringUTFChars(modelPath, path);
            return 0;
        }

        // 创建采样器（默认温度，每次生成时会按传入的 temperature 重建）
        llama_sampler* sampler = make_sampler(0.7f, LLAMA_DEFAULT_SEED);

        // 封装实例
        LlamaInstance* instance = new LlamaInstance();
        instance->model = model;
        instance->ctx = ctx;
        instance->sampler = sampler;
        instance->vocab = vocab;

        // 保存到全局指针，供 mtmd 使用
        g_model = model;

        env->ReleaseStringUTFChars(modelPath, path);

        LOGI("✅ 模型加载成功");
        LOGI("词汇表大小: %d", llama_vocab_n_tokens(vocab));
        LOGI("上下文大小: %d", llama_n_ctx(ctx));
        LOGI("[diag] n_layer=%d n_embd=%d n_params=%llu",
             llama_model_n_layer(model),
             llama_model_n_embd(model),
             (unsigned long long)llama_model_n_params(model));

        return (jlong)instance;

    } catch (const std::exception& e) {
        LOGE("❌ 初始化异常: %s", e.what());
        env->ReleaseStringUTFChars(modelPath, path);
        return 0;
    }
}

JNIEXPORT jstring JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeGenerate(
    JNIEnv* env,
    jobject /* this */,
    jlong modelPtr,
    jstring prompt,
    jint maxTokens,
    jfloat temperature
) {
    if (modelPtr == 0) {
        LOGE("❌ 无效的模型指针");
        return env->NewStringUTF("{\"error\":\"Model not initialized\"}");
    }

    LlamaInstance* instance = (LlamaInstance*)modelPtr;
    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);

    LOGI("=== 开始生成 ===");
    LOGI("提示词: %.100s", promptStr);
    LOGI("最大tokens: %d, 温度: %.2f", maxTokens, temperature);

    // 从干净上下文开始，否则上一次生成的 KV 会与本次的 token 位置冲突
    reset_context(instance, temperature);

    try {
        // 应用 Qwen3.5 聊天模板
        std::string formatted_prompt;
        const char* system_msg = "You are a helpful assistant. Output raw JSON only, without markdown code blocks or formatting.";

        // Qwen3.5 格式: <|im_start|>system\n...<|im_end|>\n<|im_start|>user\n...<|im_end|>\n<|im_start|>assistant\n
        //
        // assistant 轮末尾的 '<think>\n\n</think>\n\n' 不可省：模型自带的 chat template
        // 在非 thinking 模式下就是这么生成的（见 nativeGenerateMultimodal 里的同一处
        // 说明）。缺了它模型会进入无约束思考，长 prompt 下跑满预算也不出 EOG。
        formatted_prompt += "<|im_start|>system\n";
        formatted_prompt += system_msg;
        formatted_prompt += "\n<|im_end|>\n";
        formatted_prompt += "<|im_start|>user\n";
        formatted_prompt += promptStr;
        formatted_prompt += "\n<|im_end|>\n";
        formatted_prompt += "<|im_start|>assistant\n<think>\n\n</think>\n\n";

        const char* final_prompt = formatted_prompt.c_str();
        LOGI("格式化后提示词: %.150s", final_prompt);

        // 1. Tokenize formatted prompt
        const int n_prompt = -llama_tokenize(
            instance->vocab,
            final_prompt,
            strlen(final_prompt),
            nullptr,
            0,
            true,  // add_special
            true   // parse_special
        );

        std::vector<llama_token> prompt_tokens(n_prompt);
        if (llama_tokenize(
            instance->vocab,
            final_prompt,
            strlen(final_prompt),
            prompt_tokens.data(),
            prompt_tokens.size(),
            true,
            true
        ) < 0) {
            LOGE("❌ Tokenization 失败");
            env->ReleaseStringUTFChars(prompt, promptStr);
            return env->NewStringUTF("{\"error\":\"Tokenization failed\"}");
        }

        LOGI("Tokenized: %zu tokens", prompt_tokens.size());

        // 2. 准备 batch
        llama_batch batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());

        // 3. 处理 encoder 模型
        if (llama_model_has_encoder(instance->model)) {
            if (llama_encode(instance->ctx, batch)) {
                LOGE("❌ Encode 失败");
                env->ReleaseStringUTFChars(prompt, promptStr);
                return env->NewStringUTF("{\"error\":\"Encode failed\"}");
            }

            llama_token decoder_start_token = llama_model_decoder_start_token(instance->model);
            if (decoder_start_token == LLAMA_TOKEN_NULL) {
                decoder_start_token = llama_vocab_bos(instance->vocab);
            }
            batch = llama_batch_get_one(&decoder_start_token, 1);
        }

        // 4. 生成循环
        std::string generated_text;
        int n_pos = 0;
        int n_decode = 0;
        const int n_predict = maxTokens;

        for (; n_pos + batch.n_tokens < prompt_tokens.size() + n_predict; ) {
            // Decode
            if (llama_decode(instance->ctx, batch)) {
                LOGE("❌ Decode 失败");
                break;
            }

            n_pos += batch.n_tokens;

            // Sample 下一个 token
            llama_token new_token_id = llama_sampler_sample(instance->sampler, instance->ctx, -1);

            // 检查是否结束
            if (llama_vocab_is_eog(instance->vocab, new_token_id)) {
                LOGI("遇到 EOS token，停止生成");
                break;
            }

            // 转换 token 为文本
            char buf[256];
            int n = llama_token_to_piece(instance->vocab, new_token_id, buf, sizeof(buf), 0, true);
            if (n < 0) {
                LOGW("Token 转换失败");
                break;
            }

            generated_text.append(buf, n);
            n_decode++;

            // 准备下一个 batch
            batch = llama_batch_get_one(&new_token_id, 1);
        }

        env->ReleaseStringUTFChars(prompt, promptStr);

        LOGI("✅ 生成完成: %d tokens", n_decode);

        // 6. 后处理：剥掉思维链、去掉 Markdown 代码块围栏
        {
            const std::string before = generated_text;
            generated_text = strip_think_block(generated_text);
            if (generated_text != before) {
                LOGI("已剥离思维链: %zu -> %zu 字节", before.size(), generated_text.size());
            }

            // 去除 ```json ... ``` 代码块
            size_t start_pos = generated_text.find("```");
            if (start_pos != std::string::npos) {
                size_t end_pos = generated_text.find("```", start_pos + 3);
                if (end_pos != std::string::npos) {
                    std::string inner = generated_text.substr(start_pos + 3, end_pos - start_pos - 3);
                    if (inner.substr(0, 4) == "json") {
                        inner = inner.substr(4);
                    }
                    generated_text = inner;
                    LOGI("已去除 Markdown 代码块(```)");
                }
            }

            // 去除 `json ... ` 单引号代码块
            if (generated_text.size() > 5 && generated_text[0] == '`') {
                size_t end_backtick = generated_text.find('`', 1);
                if (end_backtick != std::string::npos) {
                    std::string inner = generated_text.substr(1, end_backtick - 1);
                    if (inner.substr(0, 4) == "json") {
                        inner = inner.substr(4);
                    } else {
                        inner = generated_text.substr(1, end_backtick - 1);
                    }
                    generated_text = inner;
                    LOGI("已去除 Markdown 代码块(`)");
                }
            }

            // 去除首尾空白

            // 去除首尾空白
            while (!generated_text.empty() && (generated_text.front() == '\n' || generated_text.front() == '\r' || generated_text.front() == ' ')) {
                generated_text.erase(generated_text.begin());
            }
            while (!generated_text.empty() && (generated_text.back() == '\n' || generated_text.back() == '\r' || generated_text.back() == ' ')) {
                generated_text.pop_back();
            }
        }

        // 7. 构建 JSON 响应
        // 转义生成的文本中的特殊字符
        std::string escaped_text;
        for (char c : generated_text) {
            if (c == '"') escaped_text += "\\\"";
            else if (c == '\\') escaped_text += "\\\\";
            else if (c == '\n') escaped_text += "\\n";
            else if (c == '\r') escaped_text += "\\r";
            else if (c == '\t') escaped_text += "\\t";
            else escaped_text += c;
        }

        std::string response =
            "{\"categories\":[\"生成文本\"],"
            "\"tags\":[\"#llama.cpp\",\"#实际推理\"],"
            "\"ocr_text\":\"\","
            "\"description\":\"" + escaped_text + "\","
            "\"confidence\":0.9,"
            "\"labels\":[{\"label\":\"LLM生成\",\"confidence\":0.9}]}";

        return utf8_to_jstring(env, response.c_str());

    } catch (const std::exception& e) {
        LOGE("❌ 生成异常: %s", e.what());
        env->ReleaseStringUTFChars(prompt, promptStr);
        return env->NewStringUTF("{\"error\":\"Generation exception\"}");
    }
}

JNIEXPORT void JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeRelease(
    JNIEnv* env,
    jobject /* this */,
    jlong modelPtr
) {
    if (modelPtr == 0) {
        return;
    }

    LOGI("=== 释放资源 ===");

    LlamaInstance* instance = (LlamaInstance*)modelPtr;

    // mtmd 内部持有对模型的引用（视觉编码器与主模型共享权重），必须先于
    // llama_model_free 释放，否则是在用已销毁的模型。
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
        g_has_mmproj = false;
    }

    if (instance->sampler) {
        llama_sampler_free(instance->sampler);
    }

    if (instance->ctx) {
        llama_free(instance->ctx);
    }

    if (instance->model) {
        llama_model_free(instance->model);
    }

    delete instance;
    g_model = nullptr;

    LOGI("✅ 资源已释放");
}

JNIEXPORT jstring JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeGetVersion(
    JNIEnv* env,
    jobject /* this */
) {
    return env->NewStringUTF("llama.cpp JNI v1.3 (ADSP_LIBRARY_PATH + device fallback - 2026-09-11)");
}

// 指定文件日志路径。必须在其它调用之前设置，否则原生日志进不了文件
// （见 jni_log_line 的说明）。
JNIEXPORT void JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeSetLogFile(
    JNIEnv* env,
    jobject /* this */,
    jstring jpath
) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    {
        std::lock_guard<std::mutex> lock(g_log_mutex);
        // 每次进程内首次设置时清空旧文件，保证读到的是本次运行
        std::string p(path);
        if (g_log_path != p) {
            FILE* f = fopen(p.c_str(), "w");
            if (f) fclose(f);
        }
        g_log_path = p;
    }
    env->ReleaseStringUTFChars(jpath, path);

    // 立刻安装 ggml 日志回调。
    //
    // 顺序很重要：后端模块的加载发生在 nativeInit 之前，而加载失败的原因是以
    // GGML_LOG_ERROR/DEBUG 发出的。如果等到 nativeInit 才装回调，这段诊断就会走
    // 默认 stderr（在这台设备上拿不到），排查时只能看到"加载失败"而没有原因。
    llama_log_set(llama_log_to_logcat, nullptr);

    // mtmd / clip（视觉塔）用的是**另一套** logger，默认回调是
    // `clip_log_callback_default`，实现就是 `fputs(text, stderr)`。
    // Android 上 stderr 被丢弃，所以不装这个回调，整个多模态侧（视觉预处理、
    // 投影器选择、图像嵌入编码）的警告与错误全都不可见 —— 排查多模态输出异常时
    // 会完全瞎掉。
    mtmd_log_set(llama_log_to_logcat, nullptr);

    LOGI("原生文件日志已启用（ggml + mtmd 日志回调已安装）");
}

// 显式设置 ADSP_LIBRARY_PATH，供 Kotlin 侧在加载 NPU 后端模块**之前**调用。
//
// 顺序是硬要求：FastRPC 在第一次打开 CDSP 会话时读取该变量并据此解析 DSP skel，
// 之后再设就来不及了。所以不能依赖 nativeLoadBackendsFrom() 顺带设置 —— 那个调用
// 排在 nativeLoadBackendByPath() 之后，而后者正是触发会话创建的地方。
//
// 传 null 或空串则清除该变量（用于"先清理再重设"，避免多次运行叠加出超长路径）。
JNIEXPORT jint JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeSetAdspLibraryPath(
    JNIEnv* env,
    jobject /* this */,
    jstring jpath
) {
    if (jpath == nullptr) {
        unsetenv("ADSP_LIBRARY_PATH");
        LOGI("已清除 ADSP_LIBRARY_PATH");
        return 0;
    }

    const char* p = env->GetStringUTFChars(jpath, nullptr);
    int rc = 0;
    if (p != nullptr && p[0] != '\0') {
        rc = setenv("ADSP_LIBRARY_PATH", p, 1);
        if (rc == 0) {
            LOGI("ADSP_LIBRARY_PATH=%s", p);
        } else {
            LOGE("setenv(ADSP_LIBRARY_PATH) 失败: %s", std::strerror(errno));
        }
    } else {
        unsetenv("ADSP_LIBRARY_PATH");
        LOGI("ADSP_LIBRARY_PATH 置空");
    }
    if (p != nullptr) env->ReleaseStringUTFChars(jpath, p);
    return rc == 0 ? 1 : 0;
}

// 设置 flash attention 模式，必须在 nativeInit 之前调用。
// 0=自动（llama.cpp 默认）1=强制关闭 2=强制开启。
JNIEXPORT jint JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeSetFlashAttnMode(
    JNIEnv* /* env */,
    jobject /* this */,
    jint mode
) {
    g_flash_attn_mode = mode;
    LOGI("flash_attn_mode 置为 %d", mode);
    return g_flash_attn_mode;
}

// 设置任意环境变量，必须在加载后端模块 / 建上下文之前调用。
//
// 存在理由：ggml 各后端把调优与规避开关全部放在环境变量里（Vulkan 有 30 多个，
// 见 ggml-vulkan.cpp 的 getenv）。App 进程设不了环境变量，只能在这里 setenv。
// ADSP_LIBRARY_PATH 走的是同一条通道，这里把它一般化，用于定位
// "Vulkan offload 任意层就输出乱码" 到底是哪个算子/优化开关引起的。
JNIEXPORT jint JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeSetEnv(
    JNIEnv* env,
    jobject /* this */,
    jstring jname,
    jstring jvalue
) {
    if (jname == nullptr) return 0;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    if (name == nullptr || name[0] == '\0') {
        if (name) env->ReleaseStringUTFChars(jname, name);
        return 0;
    }
    if (jvalue == nullptr) {
        unsetenv(name);
        LOGI("env 清除: %s", name);
        env->ReleaseStringUTFChars(jname, name);
        return 1;
    }
    const char* value = env->GetStringUTFChars(jvalue, nullptr);
    int rc = setenv(name, value != nullptr ? value : "", 1);
    if (rc == 0) {
        LOGI("env 设置: %s=%s", name, value != nullptr ? value : "");
    } else {
        LOGE("setenv(%s) 失败: %s", name, std::strerror(errno));
    }
    if (value) env->ReleaseStringUTFChars(jvalue, value);
    env->ReleaseStringUTFChars(jname, name);
    return rc == 0 ? 1 : 0;
}

// 设置视觉塔单独的线程数（<=0 表示跟随 nativeInit 的 nThreads）。
// 必须在 nativeLoadMmproj 之前调用。
JNIEXPORT jint JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeSetMmprojThreads(
    JNIEnv* /* env */,
    jobject /* this */,
    jint nThreads
) {
    g_mmproj_threads = nThreads;
    LOGI("mmproj 线程数置为 %d（<=0 表示跟随 nThreads=%d）", nThreads, g_n_threads);
    return g_mmproj_threads;
}

// KV cache 量化：0=默认(f16)，1=q8_0，2=q4_0。必须在 nativeInit 之前调用。
JNIEXPORT jint JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeSetKvQuant(
    JNIEnv* /* env */,
    jobject /* this */,
    jint mode
) {
    g_kv_quant = mode;
    LOGI("KV 量化模式置为 %d", mode);
    return g_kv_quant;
}

// 视觉塔是否交给加速后端，必须在 nativeLoadMmproj 之前调用。
// 1 表示让 clip 也尝试用加速设备。目的是验证"视觉塔能否上 NPU"——
// 它是单张分析里最长的一段（约 34 秒 / 60 秒）。风险是可能挂死，所以做成开关
// 而不是默认值；失败时 native.log 会停在 "mtmd_helper_eval_chunks 开始"。
JNIEXPORT jint JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeSetMmprojUseGpu(
    JNIEnv* /* env */,
    jobject /* this */,
    jint useGpu
) {
    g_mmproj_use_gpu = useGpu;
    LOGI("mmproj use_gpu 置为 %d", useGpu);
    return g_mmproj_use_gpu;
}

// 回读 ADSP_LIBRARY_PATH，用于确认真实生效值（诊断用）。
JNIEXPORT jstring JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeGetAdspLibraryPath(
    JNIEnv* env,
    jobject /* this */
) {
    const char* v = getenv("ADSP_LIBRARY_PATH");
    return env->NewStringUTF(v != nullptr ? v : "");
}

// 从给定目录显式加载 ggml 后端模块。
//
// 为什么不用 ggml_backend_load_all()：它只搜编译期 GGML_BACKEND_DIR、可执行文件
// 目录和 cwd（见 ggml-backend-reg.cpp:487-493）。在 Android App 里这三者都不可控：
// GGML_BACKEND_DIR 编译期为空，可执行文件是 zygote，cwd 是 "/"。实机验证过的可靠
// 通道是 ggml_backend_load_all_from_path(dir)，配合 applicationInfo.nativeLibraryDir。
//
// 加载器只认 libggml-<name>-<variant>.so 这一命名模式（同文件 483 行
// file_prefix = prefix + name + "-"），所以 hexagon 模块以 "-0" 结尾随 APK 分发。
//
// ---------------------------------------------------------------------------
// ADSP_LIBRARY_PATH —— 这一句是 Hexagon NPU 在 App 内能否工作的分水岭
// ---------------------------------------------------------------------------
// Hexagon 的 DSP 侧 skel（libggml-htp-v79.so）不在 APK 进程里执行，而是由
// FastRPC 守护进程在 CDSP 上加载。守护进程按**文件名**查找 skel，搜索路径来自
// 环境变量 ADSP_LIBRARY_PATH；App 通过 shell 设不了它，于是会话创建失败：
//
//     ggml-hex: failed to open session 0 : error 0x80000406   (AEE_EUNABLETOLOAD)
//
// 这正是本项目此前两轮 NPU 尝试卡住的原因。GenieX（com.qualcomm.qti:geniex-android）
// 的 native 侧做法给出了答案，其日志：
//
//     [plugins/llama_cpp/src/plugin.cpp:91:LlamaPlugin]
//       Setting ADSP_LIBRARY_PATH to /data/app/.../lib/arm64
//
// 即在本进程内 setenv，指向 APK 的 nativeLibraryDir。随后 skel 被成功打开：
//
//     Successfully opened file /data/app/.../lib/arm64/./libggml-htp-v79.so
//     ggml-hex: Hexagon Arch version v79
//     ggml-hex: HTP0 new session : session-id 0 domain-id 3 uri file:///libggml-htp-v79.so?...
//
// setenv 必须发生在 FastRPC 初始化（即第一次打开 CDSP 会话）之前，所以放在这里、
// 放在 ggml_backend_load_all_from_path() 之前，而不是等 nativeInit()。
//
// 前置条件是 .so 必须真实存在于文件系统上（守护进程读不到 APK 内的压缩条目），
// 这由 build.gradle.kts 的 packaging { jniLibs { useLegacyPackaging = true } } 保证。
JNIEXPORT jint JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeLoadBackendsFrom(
    JNIEnv* env,
    jobject /* this */,
    jstring jbackend_dir
) {
    const char* dir = env->GetStringUTFChars(jbackend_dir, nullptr);
    LOGI("加载 ggml 后端，目录: %s", dir);

    // skel 搜索路径。追加而非覆盖：若将来需要同时暴露多个目录，用冒号分隔。
    if (dir != nullptr && dir[0] != '\0') {
        if (setenv("ADSP_LIBRARY_PATH", dir, 1) == 0) {
            LOGI("已设置 ADSP_LIBRARY_PATH=%s（Hexagon skel 搜索路径）", dir);
        } else {
            LOGE("setenv(ADSP_LIBRARY_PATH) 失败: %s", strerror(errno));
        }
        // GGML_BACKEND_PATH 是 ggml_backend_load_all() 的通道；此处用的是显式目录
        // 扫描，设置它只为让后端模块内部若调用 load_all 时也能找到同级模块。
        setenv("GGML_BACKEND_PATH", dir, 1);
    }

    ggml_backend_load_all_from_path(dir);

    const size_t n = ggml_backend_reg_count();
    LOGI("后端注册表条目: %zu", n);
    for (size_t i = 0; i < n; ++i) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        if (reg == nullptr) continue;
        LOGI("  [%zu] %s devices=%zu", i,
             ggml_backend_reg_name(reg), ggml_backend_reg_dev_count(reg));
    }

    env->ReleaseStringUTFChars(jbackend_dir, dir);
    return (jint)n;
}

// 按完整路径加载单个后端模块。
//
// 存在的理由：目录扫描（ggml_backend_load_all_from_path）在 Release 构建里是静默的
// （ggml-backend-reg.cpp:567-571 按 NDEBUG 决定 silent），模块 dlopen 失败时只字不提，
// 排查只能靠猜。ggml_backend_load(path) 走的是非静默分支，会把真实原因打到日志。
// Android 上模块 dlopen 失败最常见的原因是它有 NEEDED 依赖在 APK 里不存在。
JNIEXPORT jint JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeLoadBackendByPath(
    JNIEnv* env,
    jobject /* this */,
    jstring jpath
) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("显式加载后端模块: %s", path);

    ggml_backend_reg_t reg = ggml_backend_load(path);
    if (reg == nullptr) {
        LOGE("后端模块加载失败: %s (真实原因见上一条 ggml 日志)", path);
        env->ReleaseStringUTFChars(jpath, path);
        return 0;
    }

    LOGI("后端模块加载成功: name=%s devices=%zu",
         ggml_backend_reg_name(reg), ggml_backend_reg_dev_count(reg));
    env->ReleaseStringUTFChars(jpath, path);
    return 1;
}

// 报告本进程实际注册成功、且能看到的加速后端。
// 这是回答"GPU 到底有没有生效"的唯一可信来源：llama.cpp 在加速后端缺失时会
// 静默回退到 CPU，只看 n_gpu_layers 会得出错误结论。
JNIEXPORT jstring JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeDescribeBackends(
    JNIEnv* env,
    jobject /* this */
) {
    const std::string report = describe_backends();
    LOGI("后端报告: %s", report.c_str());
    return utf8_to_jstring(env, report.c_str());
}

// ============================================================
//  多模态：加载 mmproj 视觉编码器
// ============================================================
JNIEXPORT jboolean JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeLoadMmproj(
    JNIEnv* env,
    jobject /* this */,
    jstring jmmproj_path
) {
    const auto* mmproj_path = env->GetStringUTFChars(jmmproj_path, nullptr);
    LOGI("加载 mmproj: %s", mmproj_path);

    if (!g_model) {
        LOGE("模型未加载，无法加载 mmproj");
        env->ReleaseStringUTFChars(jmmproj_path, mmproj_path);
        return JNI_FALSE;
    }

    mtmd_context_params mparams = mtmd_context_params_default();
    // 视觉编码器**不放到加速后端**。
    //
    // 实测：use_gpu=true 时 mtmd_helper_eval_chunks 在 Adreno/Vulkan 上永久挂死
    // （原生日志停在 "mtmd_helper_eval_chunks 开始" 后 200 秒毫无进展，CPU 占用 0%）。
    //
    // ⚠️ 那条结论是在 Vulkan 还是自动链成员时得出的。Vulkan 已移出自动链，理论上
    // 现在 use_gpu=true 会把视觉塔放到 HTP0（NPU）—— 视觉塔是 ViT，主要是 matmul，
    // 而 HTP skel 的算子表里**没有 conv2d/im2col**，patch_embed 那一步大概率回落 CPU。
    // 这条路尚未重测，收益（视觉塔 34 秒）值得一试，但先不动默认值。
    mparams.use_gpu          = (g_mmproj_use_gpu != 0);
    mparams.print_timings    = false;
    // 跟随 nativeInit 的线程数，不再硬编码。单张分析里最长的一段就在视觉塔编码，
    // 把它钉死在 4 线程（设备有 8 核）是纯白扔的时间。
    const int mmproj_threads = (g_mmproj_threads > 0) ? g_mmproj_threads : g_n_threads;
    mparams.n_threads        = mmproj_threads;
    mparams.warmup           = true;
    mparams.flash_attn_type  = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    LOGI("视觉塔线程数: %d（跟随 nativeInit 的 nThreads）", mparams.n_threads);

    g_mtmd_ctx = mtmd_init_from_file(mmproj_path, g_model, mparams);
    env->ReleaseStringUTFChars(jmmproj_path, mmproj_path);

    if (!g_mtmd_ctx) {
        LOGE("mmproj 加载失败");
        g_has_mmproj = false;
        return JNI_FALSE;
    }

    g_has_mmproj = true;
    LOGI("mmproj 加载成功, vision=%d, audio=%d, use_gpu=%d",
         mtmd_support_vision(g_mtmd_ctx),
         mtmd_support_audio(g_mtmd_ctx),
         (int)mparams.use_gpu);
    return JNI_TRUE;
}

// ============================================================
//  多模态：图片 + 文本生成
// ============================================================
JNIEXPORT jstring JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeGenerateMultimodal(
    JNIEnv* env,
    jobject /* this */,
    jlong modelPtr,
    jbyteArray jimage_data,
    jstring jprompt,
    jint maxTokens,
    jfloat temperature
) {
    if (modelPtr == 0) {
        return env->NewStringUTF("{\"error\":\"Model not initialized\"}");
    }
    if (!g_has_mmproj || !g_mtmd_ctx) {
        return env->NewStringUTF("{\"error\":\"mmproj not loaded\"}");
    }

    LlamaInstance* instance = (LlamaInstance*)modelPtr;

    // 每次多模态推理都必须从干净上下文开始：mtmd 会从位置 0 重新写入图像与文本
    // token，残留的 KV 会让第二次 llama_decode 直接失败。
    reset_context(instance, temperature);

    // 1. 获取图片数据
    jsize image_len = env->GetArrayLength(jimage_data);
    jbyte* image_bytes = env->GetByteArrayElements(jimage_data, nullptr);
    if (!image_bytes || image_len == 0) {
        env->ReleaseByteArrayElements(jimage_data, image_bytes, JNI_ABORT);
        return env->NewStringUTF("{\"error\":\"Invalid image data\"}");
    }
    LOGI("多模态生成: image=%d bytes, maxTokens=%d, temp=%.2f", image_len, maxTokens, temperature);

    // 图片指纹 + 提示词长度。
    //
    // 保留理由：排查"同一提示词、同一模型，换张图结果就变"这类问题时，必须能确认
    // 送进去的到底是哪张图。完整 dump 图片数据会淹没日志，所以用累加校验和。
    // 完整提示词不打（太长），需要时看下面 chunk 组成的 TEXT n_tokens 即可。
    {
        const unsigned char* p = (const unsigned char*)env->GetByteArrayElements(jimage_data, nullptr);
        unsigned long long sum = 0;
        int jw = 0, jh = 0;
        bool have_dims = false;
        if (p != nullptr) {
            for (jsize k = 0; k < image_len; k++) sum = sum * 131ULL + p[k];
            have_dims = jpeg_dimensions(p, (size_t)image_len, &jw, &jh);
            // 只释放一次，且必须在读完尺寸之后
            env->ReleaseByteArrayElements(jimage_data, (jbyte*)p, JNI_ABORT);
        }
        const char* pr = env->GetStringUTFChars(jprompt, nullptr);
        LOGI("[diag] 送入模型: jpeg=%dx%d imageBytes=%d imageHash=%016llx promptChars=%zu",
             have_dims ? jw : -1, have_dims ? jh : -1,
             image_len, sum, pr ? strlen(pr) : 0);
        // 完整提示词。只在需要对比"两条路径到底送了什么"时打开：分析为什么基准路径
        // 输出正确、相册路径乱码时，唯一还没被真正比对过的量就是这段文本。
        if (pr != nullptr) {
            LOGI("[diag] prompt全文:\n<<<\n%s\n>>>", pr);
            env->ReleaseStringUTFChars(jprompt, pr);
        }
    }

    // 2. 创建 mtmd bitmap
    auto wrapper = mtmd_helper_bitmap_init_from_buf(
        g_mtmd_ctx, (const unsigned char*)image_bytes, (size_t)image_len, false);
    env->ReleaseByteArrayElements(jimage_data, image_bytes, JNI_ABORT);
    if (!wrapper.bitmap) {
        return env->NewStringUTF("{\"error\":\"Failed to decode image\"}");
    }

    // 3. 获取 prompt
    const auto* prompt = env->GetStringUTFChars(jprompt, nullptr);

    // 4. 构建带图片标记的 prompt
    const char* marker = mtmd_get_marker(g_mtmd_ctx);
    if (!marker) marker = mtmd_default_marker();
    std::string raw_prompt = std::string(marker) + "\n" + std::string(prompt);

    // 5. 聊天模板
    //
    // assistant 轮必须**预先闭合 think 块**，这与模型自带的 chat template 一致：
    //   {%- if enable_thinking is defined and enable_thinking is true %}
    //       {{- '<think>\n' }}
    //   {%- else %}
    //       {{- '<think>\n\n</think>\n\n' }}      <-- 默认走这条
    //   {%- endif %}
    //
    // 只写 '<|im_start|>assistant\n'（本函数早前的做法）等于让 Qwen3.5 进入不受约束
    // 的思考模式：实测生产路径因此跑满 512 个 token 也不产生 EOG，输出退化成多语言
    // 乱码。补上闭合块后模型会直接作答。这与采样器缺重复惩罚是两个独立诱因，都要修。
    std::string fmt;
    fmt += "<|im_start|>system\nYou are a helpful assistant.\n<|im_end|>\n";
    fmt += "<|im_start|>user\n" + raw_prompt + "\n<|im_end|>\n";
    fmt += "<|im_start|>assistant\n<think>\n\n</think>\n\n";

    // 6. mtmd tokenize
    mtmd_input_text text;
    text.text          = fmt.c_str();
    text.text_len      = fmt.size();
    text.add_special   = true;
    text.parse_special = true;

    LOGI("[stage] mtmd_input_chunks_init 开始");
    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    if (!chunks) {
        mtmd_bitmap_free(wrapper.bitmap);
        env->ReleaseStringUTFChars(jprompt, prompt);
        return env->NewStringUTF("{\"error\":\"Failed to create chunks\"}");
    }

    mtmd_bitmap* bitmaps[] = { wrapper.bitmap };
    LOGI("[stage] mtmd_tokenize 开始");
    int32_t res = mtmd_tokenize(g_mtmd_ctx, chunks, &text, (const mtmd_bitmap**)bitmaps, 1);
    LOGI("[stage] mtmd_tokenize 结束: %d", res);
    mtmd_bitmap_free(wrapper.bitmap);
    if (res != 0) {
        mtmd_input_chunks_free(chunks);
        env->ReleaseStringUTFChars(jprompt, prompt);
        return env->NewStringUTF("{\"error\":\"mtmd_tokenize failed\"}");
    }

    // 诊断：打印 chunk 组成。
    //
    // 必要性：视觉塔能编出正确的嵌入（实测 shape [2048, 196]），但 n_past 只增加
    // 44 —— 两者对不上时，问题一定出在"图像 chunk 有没有被真正喂给 LLM"。这一行
    // 直接给出每个 chunk 的类型与 token 数，把猜测变成观测。
    {
        const size_t n_chunks = mtmd_input_chunks_size(chunks);
        LOGI("[diag] chunks=%zu", n_chunks);
        for (size_t ci = 0; ci < n_chunks; ci++) {
            const mtmd_input_chunk* c = mtmd_input_chunks_get(chunks, ci);
            const enum mtmd_input_chunk_type t = mtmd_input_chunk_get_type(c);
            if (t == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                size_t n_tok = 0;
                mtmd_input_chunk_get_tokens_text(c, &n_tok);
                LOGI("[diag]   chunk[%zu] TEXT n_tokens=%zu", ci, n_tok);
            } else if (t == MTMD_INPUT_CHUNK_TYPE_IMAGE) {
                const mtmd_image_tokens* it = mtmd_input_chunk_get_tokens_image(c);
                LOGI("[diag]   chunk[%zu] IMAGE n_tokens=%zu (placeholder=%d)",
                     ci, it ? mtmd_image_tokens_get_n_tokens(it) : 0,
                     it ? (int)mtmd_image_tokens_get_n_pos(it) : -1);
            } else {
                LOGI("[diag]   chunk[%zu] type=%d", ci, (int)t);
            }
        }
    }

    // 7. eval chunks（用小批量避免 OOM）
    // 这是最可能卡住的一步：它会把视觉编码器与 LLM 的图一起构建并提交给后端，
    // GPU 路径上首次执行还包含大量 pipeline 创建（Adreno 上可能非常慢甚至挂死）。
    const int multimodal_batch = 64;
    llama_pos n_past = 0;
    LOGI("[stage] mtmd_helper_eval_chunks 开始 (batch=%d)", multimodal_batch);
    res = mtmd_helper_eval_chunks(g_mtmd_ctx, instance->ctx, chunks, n_past, 0, multimodal_batch, true, &n_past);
    LOGI("[stage] mtmd_helper_eval_chunks 结束: %d, n_past=%d", res, (int)n_past);
    mtmd_input_chunks_free(chunks);
    env->ReleaseStringUTFChars(jprompt, prompt);
    if (res != 0) {
        LOGE("mtmd_helper_eval_chunks 失败: %d (chunks=%d)", res, (int)n_past);
        return env->NewStringUTF("{\"error\":\"Evaluation failed\"}");
    }

    // 8. 生成循环（采样器已在第 4 步按本次 temperature 重建）
    LOGI("[stage] 生成循环开始");
    std::string result;
    int n_decode = 0;
    const int64_t gen_start_ms = (int64_t)(ggml_time_us() / 1000);
    for (int i = 0; i < maxTokens; i++) {
        llama_token id = llama_sampler_sample(instance->sampler, instance->ctx, -1);
        if (llama_vocab_is_eog(instance->vocab, id)) {
            LOGI("[diag] 生成在第 %d 个 token 遇到 EOG（正常结束）", i);
            break;
        }

        char buf[256];
        int n = llama_token_to_piece(instance->vocab, id, buf, sizeof(buf), 0, true);
        if (n < 0) {
            LOGI("[diag] token_to_piece 在第 %d 个 token 失败（id=%d, 需要 %d 字节）", i, (int)id, -n);
            break;
        }
        result.append(buf, n);
        n_decode++;

        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(instance->ctx, batch)) break;
        // 逐 token 打点：GPU 路径若卡在某个 decode 上，文件日志会停在这里
        if (i < 3 || i % 16 == 0) {
            LOGI("[stage] decode token %d ok (%.0f ms)", i,
                 (double)((int64_t)(ggml_time_us() / 1000) - gen_start_ms));
        }
    }
    {
        const int64_t gen_ms = (int64_t)(ggml_time_us() / 1000) - gen_start_ms;
        LOGI("多模态生成完成: %d tokens, 解码耗时 %lld ms (%.2f tok/s)",
             n_decode, (long long)gen_ms,
             gen_ms > 0 ? (n_decode * 1000.0 / (double)gen_ms) : 0.0);
    }

    // 9. 后处理
    {
        // 先剥思维链 —— 见 strip_think_block() 的说明，这里曾把答案整段删掉。
        const std::string before = result;
        result = strip_think_block(result);
        if (result != before) {
            LOGI("已剥离思维链: %zu -> %zu 字节", before.size(), result.size());
        }

        size_t sp = result.find("```");
        if (sp != std::string::npos) {
            size_t ep = result.find("```", sp + 3);
            if (ep != std::string::npos) {
                // 有配对的反引号，提取内部内容
                std::string inner = result.substr(sp + 3, ep - sp - 3);
                if (inner.substr(0, 4) == "json") inner = inner.substr(4);
                result = inner;
            } else {
                // 没有配对的反引号，直接删掉开头的 ```json 或 ```
                std::string prefix = result.substr(sp, 3);
                result = result.substr(sp + 3);
                if (result.substr(0, 4) == "json") result = result.substr(4);
                // 跳过开头的换行
                while (!result.empty() && (result[0] == '\n' || result[0] == '\r')) result.erase(result.begin());
            }
        }
        if (!result.empty() && result[0] == '`') {
            size_t ep2 = result.find('`', 1);
            if (ep2 != std::string::npos) {
                std::string inner = result.substr(1, ep2 - 1);
                if (inner.substr(0, 4) == "json") inner = inner.substr(4);
                result = inner;
            }
        }
        while (!result.empty() && (result.back() == '\n' || result.back() == ' ' || result.back() == '\r')) result.pop_back();
        while (!result.empty() && (result.front() == '\n' || result.front() == ' ' || result.front() == '\r')) result.erase(result.begin());
    }

    return utf8_to_jstring(env, result.c_str());
}

// ============================================================
//  流水线：把视觉塔编码与 LLM 推理解耦
// ============================================================
//
// 为什么值得做：单张分析里视觉塔（CPU）与 LLM（NPU）是串行的两段（2B + 512px 下
// 约 6 秒 + 11 秒），但二者用的是**不同硬件、不同 ggml 上下文** —— 视觉塔只用
// clip_ctx，推理只用 llama context。所以连续分析多张时，可以让第 N+1 张的视觉塔
// 与第 N 张的 LLM 推理重叠，批量耗时从 (6+11) 降到大约 max(6,11)。
//
// ⚠️ **这对单张分析没有帮助**：单张内部 prefill 依赖视觉塔的输出，无法重叠。
//
// 线程安全性的依据（读 mtmd 源码确认，不是推测）：
//   - 编码路径 mtmd_encode_chunk() 只写 `ctx->out_embd`（我们随后 memcpy 出来），
//     其余是对 ctx 配置字段的读；
//   - 推理路径 mtmd_helper_decode_image_chunk() 对 ctx 的使用**全是只读**
//     （mtmd_decode_use_mrope / mtmd_decode_use_non_causal），且用我们传入的
//     embd 拷贝，不碰 `ctx->out_embd`；
//   - llama context（KV cache）只被推理线程使用，编码路径完全不碰它。
// 因此两条路径可以并行；KV cache 的清理（reset_context）只在推理线程做。
struct PreencodedImage {
    mtmd_input_chunks * chunks = nullptr;
    std::vector<float>  embd;    // 视觉塔输出，独立于 ctx->out_embd
};

// 把 JPEG + prompt 变成 mtmd chunks。
//
// 必须与 nativeGenerateMultimodal 里的构建方式逐字一致（图像标记、聊天模板、
// 预先闭合的 think 块），否则流水线路径与单张路径的输出会不一样 ——
// 那种差异极难排查，因为两边看起来都"能跑"。
static mtmd_input_chunks * build_chunks_from_image(
        JNIEnv * env, jbyteArray jimage_data, jstring jprompt, int * out_err) {
    *out_err = 1;
    if (!g_has_mmproj || !g_mtmd_ctx) return nullptr;

    const jsize image_len = env->GetArrayLength(jimage_data);
    jbyte * image_bytes = env->GetByteArrayElements(jimage_data, nullptr);
    if (!image_bytes || image_len == 0) {
        if (image_bytes) env->ReleaseByteArrayElements(jimage_data, image_bytes, JNI_ABORT);
        return nullptr;
    }

    auto wrapper = mtmd_helper_bitmap_init_from_buf(
        g_mtmd_ctx, (const unsigned char *)image_bytes, (size_t)image_len, false);
    env->ReleaseByteArrayElements(jimage_data, image_bytes, JNI_ABORT);
    if (!wrapper.bitmap) {
        LOGE("流水线: 图片解码失败");
        return nullptr;
    }

    const char * prompt = env->GetStringUTFChars(jprompt, nullptr);
    const char * marker = mtmd_get_marker(g_mtmd_ctx);
    if (!marker) marker = mtmd_default_marker();
    std::string raw_prompt = std::string(marker) + "\n" + std::string(prompt ? prompt : "");

    std::string fmt;
    fmt += "<|im_start|>system\nYou are a helpful assistant.\n<|im_end|>\n";
    fmt += "<|im_start|>user\n" + raw_prompt + "\n<|im_end|>\n";
    fmt += "<|im_start|>assistant\n<think>\n\n</think>\n\n";

    mtmd_input_text text;
    text.text          = fmt.c_str();
    text.text_len      = fmt.size();
    text.add_special   = true;
    text.parse_special = true;

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    if (!chunks) {
        mtmd_bitmap_free(wrapper.bitmap);
        if (prompt) env->ReleaseStringUTFChars(jprompt, prompt);
        return nullptr;
    }
    mtmd_bitmap * bitmaps[] = { wrapper.bitmap };
    const int32_t res = mtmd_tokenize(g_mtmd_ctx, chunks, &text, (const mtmd_bitmap **)bitmaps, 1);
    mtmd_bitmap_free(wrapper.bitmap);
    if (prompt) env->ReleaseStringUTFChars(jprompt, prompt);
    if (res != 0) {
        LOGE("流水线: mtmd_tokenize 失败 %d", res);
        mtmd_input_chunks_free(chunks);
        return nullptr;
    }
    *out_err = 0;
    return chunks;
}

// 流水线入口 1：只做视觉塔编码，不碰 llama context。可在后台线程调用。
JNIEXPORT jlong JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeEncodeImage(
    JNIEnv * env, jobject /* this */, jbyteArray jimage_data, jstring jprompt) {
    int err = 1;
    mtmd_input_chunks * chunks = build_chunks_from_image(env, jimage_data, jprompt, &err);
    if (err != 0 || !chunks) return 0;

    auto * h = new PreencodedImage();
    h->chunks = chunks;

    const int n_embd = (int)llama_model_n_embd_inp(g_model);
    const size_t n_chunks = mtmd_input_chunks_size(chunks);
    for (size_t i = 0; i < n_chunks; i++) {
        const mtmd_input_chunk * c = mtmd_input_chunks_get(chunks, i);
        if (mtmd_input_chunk_get_type(c) != MTMD_INPUT_CHUNK_TYPE_IMAGE) continue;
        if (mtmd_encode_chunk(g_mtmd_ctx, c) != 0) {
            LOGE("流水线: mtmd_encode_chunk 失败");
            mtmd_input_chunks_free(h->chunks);
            delete h;
            return 0;
        }
        // 立刻拷贝出来：ctx->out_embd 会被下一次编码覆盖，而推理线程要用这一份。
        const float * out = mtmd_get_output_embd(g_mtmd_ctx);
        const size_t n = mtmd_input_chunk_get_n_tokens(c) * (size_t)n_embd;
        if (!out) {
            LOGE("流水线: mtmd_get_output_embd 返回空");
            mtmd_input_chunks_free(h->chunks);
            delete h;
            return 0;
        }
        h->embd.assign(out, out + n);
        LOGI("[pipeline] 已编码图像: %zu tokens x %d embd = %zu floats",
             mtmd_input_chunk_get_n_tokens(c), n_embd, n);
    }
    return (jlong)(intptr_t)h;
}

JNIEXPORT void JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeFreeEncoded(
    JNIEnv * /* env */, jobject /* this */, jlong handle) {
    auto * h = (PreencodedImage *)(intptr_t)handle;
    if (!h) return;
    if (h->chunks) mtmd_input_chunks_free(h->chunks);
    delete h;
}

// 流水线入口 2：用预先算好的 embeddings 做 prefill + 生成。
//
// eval 部分照 mtmd_helper_eval_chunk_single 的逻辑写，唯一区别是 IMAGE chunk
// 直接用 h->embd，不再调 mtmd_encode_chunk。
JNIEXPORT jstring JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeGenerateFromEncoded(
    JNIEnv * env, jobject /* this */, jlong modelPtr, jlong handle,
    jint maxTokens, jfloat temperature) {
    auto * h = (PreencodedImage *)(intptr_t)handle;
    auto * instance = (LlamaInstance *)(intptr_t)modelPtr;
    if (!h || !h->chunks || !instance) {
        return env->NewStringUTF("{\"error\":\"invalid pipeline handle\"}");
    }

    reset_context(instance, temperature);

    const int n_batch = 64;
    const size_t n_chunks = mtmd_input_chunks_size(h->chunks);
    llama_pos n_past = 0;
    const int64_t t0 = (int64_t)(ggml_time_us() / 1000);

    for (size_t i = 0; i < n_chunks; i++) {
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(h->chunks, i);
        const enum mtmd_input_chunk_type type = mtmd_input_chunk_get_type(chunk);

        if (type == MTMD_INPUT_CHUNK_TYPE_TEXT) {
            size_t n_tokens = 0;
            const llama_token * tokens = mtmd_input_chunk_get_tokens_text(chunk, &n_tokens);
            size_t k = 0;
            while (k < n_tokens) {
                llama_batch tb = llama_batch_init(n_batch, 0, 1);
                tb.n_tokens = 0;
                for (; k < n_tokens && tb.n_tokens < n_batch; k++) {
                    const int32_t j = tb.n_tokens;
                    tb.token[j] = tokens[k];
                    tb.pos[j] = n_past++;
                    tb.n_seq_id[j] = 1;
                    tb.seq_id[j][0] = 0;
                    tb.logits[j] = false;
                    tb.n_tokens++;
                }
                // 最后一个 TEXT chunk 的最后一个 token 要出 logits，否则采样无输入
                if (i == n_chunks - 1 && k >= n_tokens) {
                    tb.logits[tb.n_tokens - 1] = true;
                }
                const int32_t r = llama_decode(instance->ctx, tb);
                llama_batch_free(tb);
                if (r != 0) {
                    LOGE("流水线: text decode 失败 %d", r);
                    return env->NewStringUTF("{\"error\":\"pipeline text decode failed\"}");
                }
            }
        } else {
            const int32_t r = mtmd_helper_decode_image_chunk(
                g_mtmd_ctx, instance->ctx, chunk, h->embd.data(),
                n_past, 0, n_batch, &n_past, nullptr, nullptr);
            if (r != 0) {
                LOGE("流水线: image decode 失败 %d", r);
                return env->NewStringUTF("{\"error\":\"pipeline image decode failed\"}");
            }
        }
        LOGI("[pipeline] chunk %zu/%zu 完成, n_past=%d", i + 1, n_chunks, (int)n_past);
    }
    LOGI("[pipeline] 预填充完成 %lld ms, n_past=%d",
         (long long)((int64_t)(ggml_time_us() / 1000) - t0), (int)n_past);

    // 生成循环与 nativeGenerateMultimodal 完全一致
    std::string result;
    int n_decode = 0;
    const int64_t gen_start_ms = (int64_t)(ggml_time_us() / 1000);
    for (int i = 0; i < maxTokens; i++) {
        llama_token id = llama_sampler_sample(instance->sampler, instance->ctx, -1);
        if (llama_vocab_is_eog(instance->vocab, id)) {
            LOGI("[pipeline] 生成在第 %d 个 token 遇到 EOG", i);
            break;
        }
        char buf[256];
        int n = llama_token_to_piece(instance->vocab, id, buf, sizeof(buf), 0, true);
        if (n < 0) break;
        result.append(buf, n);
        n_decode++;
        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(instance->ctx, batch)) break;
    }
    {
        const int64_t gen_ms = (int64_t)(ggml_time_us() / 1000) - gen_start_ms;
        LOGI("[pipeline] 生成完成: %d tokens, %lld ms (%.2f tok/s)", n_decode,
             (long long)gen_ms, gen_ms > 0 ? (n_decode * 1000.0 / (double)gen_ms) : 0.0);
    }

    result = strip_think_block(result);
    while (!result.empty() && (result.back() == '\n' || result.back() == ' ' || result.back() == '\r')) result.pop_back();
    while (!result.empty() && (result.front() == '\n' || result.front() == ' ' || result.front() == '\r')) result.erase(result.begin());
    return utf8_to_jstring(env, result.c_str());
}

} // extern "C"
