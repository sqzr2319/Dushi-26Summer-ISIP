#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// 封装 llama.cpp 实例
struct LlamaInstance {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    llama_sampler* sampler = nullptr;
    const llama_vocab* vocab = nullptr;
};

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

    try {
        // 加载动态后端
        ggml_backend_load_all();

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
        ctx_params.n_batch = 512;
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
        // 1. Tokenize prompt
        const int n_prompt = -llama_tokenize(
            instance->vocab,
            promptStr,
            strlen(promptStr),
            nullptr,
            0,
            true,  // add_special
            true   // parse_special
        );

        std::vector<llama_token> prompt_tokens(n_prompt);
        if (llama_tokenize(
            instance->vocab,
            promptStr,
            strlen(promptStr),
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

        // 5. 构建 JSON 响应
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

    LOGI("✅ 资源已释放");
}

JNIEXPORT jstring JNICALL
Java_com_example_isip_data_ai_LlamaCppNative_nativeGetVersion(
    JNIEnv* env,
    jobject /* this */
) {
    return env->NewStringUTF("llama.cpp JNI v1.0 (full inference - 2026-07-14)");
}

} // extern "C"
