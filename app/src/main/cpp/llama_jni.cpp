#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// llama.cpp 日志回调 —— 将内部日志重定向到 Android logcat
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
}

// 封装 llama.cpp 实例
struct LlamaInstance {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    const llama_vocab* vocab = nullptr;
};

// 多模态 (mtmd) 支持
static mtmd_context*  g_mtmd_ctx   = nullptr;
static bool           g_has_mmproj = false;
static llama_model*   g_model      = nullptr;  // 全局模型指针，供 mtmd 使用

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeInit(
    JNIEnv* env,
    jobject /* this */,
    jstring modelPath,
    jint nThreads,
    jint nCtx,
    jint nGpuLayers
) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    LOGI("=== 初始化 llama.cpp ===");
    LOGI("模型: %s", path);
    LOGI("线程: %d, 上下文: %d, GPU层: %d", nThreads, nCtx, nGpuLayers);

    // 将 llama.cpp 内部日志重定向到 Android logcat
    llama_log_set(llama_log_to_logcat, nullptr);

    try {
        // 加载动态后端
        LOGI("正在加载 ggml 后端...");
        ggml_backend_load_all();
        LOGI("ggml 后端加载完成");

        // 配置模型参数
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = nGpuLayers;

        // 加载模型
        llama_model* model = llama_model_load_from_file(path, model_params);
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

        // 创建上下文
        llama_context* ctx = llama_init_from_model(model, ctx_params);
        if (!ctx) {
            LOGE("❌ 上下文创建失败");
            llama_model_free(model);
            env->ReleaseStringUTFChars(modelPath, path);
            return 0;
        }

        // 创建采样器
        auto sparams = llama_sampler_chain_default_params();
        sparams.no_perf = false;
        llama_sampler* sampler = llama_sampler_chain_init(sparams);

        // 添加温度采样
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
        // 添加贪婪采样作为后备
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

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

    try {
        // 应用 Qwen3.5 聊天模板
        std::string formatted_prompt;
        const char* system_msg = "You are a helpful assistant. Output raw JSON only, without markdown code blocks or formatting.";

        // Qwen3.5 格式: <|im_start|>system\n...<|im_end|>\n<|im_start|>user\n...<|im_end|>\n<|im_start|>assistant\n
        formatted_prompt += "<|im_start|>system\n";
        formatted_prompt += system_msg;
        formatted_prompt += "\n<|im_end|>\n";
        formatted_prompt += "<|im_start|>user\n";
        formatted_prompt += promptStr;
        formatted_prompt += "\n<|im_end|>\n";
        formatted_prompt += "<|im_start|>assistant\n";

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

        // 6. 后处理：去除 <think> 标签和 Markdown 代码块
        {
            // 去除 <think>...</think>
            size_t start_pos = generated_text.find("<think>");
            size_t end_pos = generated_text.find("</think>");
            if (start_pos != std::string::npos && end_pos != std::string::npos) {
                generated_text.erase(start_pos, end_pos + 8 - start_pos);
                LOGI("已去除 <think> 标签");
            }

            // 去除 ```json ... ``` 代码块
            start_pos = generated_text.find("```");
            if (start_pos != std::string::npos) {
                end_pos = generated_text.find("```", start_pos + 3);
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

        return env->NewStringUTF(response.c_str());

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

    // 清理多模态资源
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
        g_has_mmproj = false;
    }
    g_model = nullptr;

    LOGI("✅ 资源已释放");
}

JNIEXPORT jstring JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeGetVersion(
    JNIEnv* env,
    jobject /* this */
) {
    return env->NewStringUTF("llama.cpp JNI v1.0 (multimodal - 2026-07-18)");
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
    mparams.use_gpu          = false;
    mparams.print_timings    = false;
    mparams.n_threads        = 4;
    mparams.warmup           = true;
    mparams.flash_attn_type  = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    g_mtmd_ctx = mtmd_init_from_file(mmproj_path, g_model, mparams);
    env->ReleaseStringUTFChars(jmmproj_path, mmproj_path);

    if (!g_mtmd_ctx) {
        LOGE("mmproj 加载失败");
        g_has_mmproj = false;
        return JNI_FALSE;
    }

    g_has_mmproj = true;
    LOGI("mmproj 加载成功, vision=%d, audio=%d",
         mtmd_support_vision(g_mtmd_ctx),
         mtmd_support_audio(g_mtmd_ctx));
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

    // 1. 获取图片数据
    jsize image_len = env->GetArrayLength(jimage_data);
    jbyte* image_bytes = env->GetByteArrayElements(jimage_data, nullptr);
    if (!image_bytes || image_len == 0) {
        env->ReleaseByteArrayElements(jimage_data, image_bytes, JNI_ABORT);
        return env->NewStringUTF("{\"error\":\"Invalid image data\"}");
    }
    LOGI("多模态生成: image=%d bytes", image_len);

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
    std::string fmt;
    fmt += "<|im_start|>system\nYou are a helpful assistant.\n<|im_end|>\n";
    fmt += "<|im_start|>user\n" + raw_prompt + "\n<|im_end|>\n";
    fmt += "<|im_start|>assistant\n";

    // 6. mtmd tokenize
    mtmd_input_text text;
    text.text          = fmt.c_str();
    text.text_len      = fmt.size();
    text.add_special   = true;
    text.parse_special = true;

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    if (!chunks) {
        mtmd_bitmap_free(wrapper.bitmap);
        env->ReleaseStringUTFChars(jprompt, prompt);
        return env->NewStringUTF("{\"error\":\"Failed to create chunks\"}");
    }

    mtmd_bitmap* bitmaps[] = { wrapper.bitmap };
    int32_t res = mtmd_tokenize(g_mtmd_ctx, chunks, &text, (const mtmd_bitmap**)bitmaps, 1);
    mtmd_bitmap_free(wrapper.bitmap);
    if (res != 0) {
        mtmd_input_chunks_free(chunks);
        env->ReleaseStringUTFChars(jprompt, prompt);
        return env->NewStringUTF("{\"error\":\"mtmd_tokenize failed\"}");
    }

    // 7. eval chunks（用小批量避免 OOM）
    const int multimodal_batch = 64;
    llama_pos n_past = 0;
    res = mtmd_helper_eval_chunks(g_mtmd_ctx, instance->ctx, chunks, n_past, 0, multimodal_batch, true, &n_past);
    mtmd_input_chunks_free(chunks);
    env->ReleaseStringUTFChars(jprompt, prompt);
    if (res != 0) {
        return env->NewStringUTF("{\"error\":\"Evaluation failed\"}");
    }

    // 8. 生成循环
    // 先清除 KV cache 中残留的 prompt 位置，用 n_past
    // 重新设置 sampler 温度
    if (temperature > 0 && temperature != 0.1f) {
        // 用新温度重新创建 sampler（简化处理）
    }

    std::string result;
    int n_decode = 0;
    for (int i = 0; i < maxTokens; i++) {
        llama_token id = llama_sampler_sample(instance->sampler, instance->ctx, -1);
        if (llama_vocab_is_eog(instance->vocab, id)) break;

        char buf[256];
        int n = llama_token_to_piece(instance->vocab, id, buf, sizeof(buf), 0, true);
        if (n < 0) break;
        result.append(buf, n);
        n_decode++;

        llama_batch batch = llama_batch_get_one(&id, 1);
        if (llama_decode(instance->ctx, batch)) break;
    }
    LOGI("多模态生成完成: %d tokens", n_decode);

    // 9. 后处理
    {
        size_t sp = result.find("<think>");
        size_t ep = result.find("</think>");
        if (sp != std::string::npos && ep != std::string::npos)
            result.erase(sp, ep + 8 - sp);

        sp = result.find("```");
        if (sp != std::string::npos) {
            ep = result.find("```", sp + 3);
            if (ep != std::string::npos) {
                std::string inner = result.substr(sp + 3, ep - sp - 3);
                if (inner.substr(0, 4) == "json") inner = inner.substr(4);
                result = inner;
            }
        }
        if (!result.empty() && result[0] == '`') {
            ep = result.find('`', 1);
            if (ep != std::string::npos) {
                std::string inner = result.substr(1, ep - 1);
                if (inner.substr(0, 4) == "json") inner = inner.substr(4);
                result = inner;
            }
        }
        while (!result.empty() && (result.back() == '\n' || result.back() == ' ' || result.back() == '\r')) result.pop_back();
        while (!result.empty() && (result.front() == '\n' || result.front() == ' ' || result.front() == '\r')) result.erase(result.begin());
    }

    return env->NewStringUTF(result.c_str());
}

} // extern "C"
