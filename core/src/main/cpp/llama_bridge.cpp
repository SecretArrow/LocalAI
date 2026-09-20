// llama_bridge.cpp — JNI bridge between LlamaCpuBackend (Kotlin) and llama.cpp.
// API target: llama.cpp release b4755 (see CMakeLists.txt).
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "llama.h"

#define TAG "LocalAI-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;

struct BridgeModel {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::mutex mutex;
};

void llamaLogCallback(ggml_log_level level, const char *text, void * /*user_data*/) {
    if (text == nullptr) return;
    const android_LogPriority prio = (level == GGML_LOG_LEVEL_ERROR) ? ANDROID_LOG_ERROR
                                     : (level == GGML_LOG_LEVEL_WARN) ? ANDROID_LOG_WARN
                                                                       : ANDROID_LOG_INFO;
    __android_log_print(prio, "llama.cpp", "%s", text);
}

std::string toStdString(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Calls a kotlin `(String) -> Boolean` lambda. Returning false stops generation.
bool callKotlinCallback(JNIEnv *env, jobject callback, jmethodID invokeId, const std::string &text) {
    if (callback == nullptr || invokeId == nullptr) return true;
    jstring jtext = env->NewStringUTF(text.c_str());
    if (jtext == nullptr) return true;
    jobject result = env->CallObjectMethod(callback, invokeId, jtext);
    env->DeleteLocalRef(jtext);
    bool keepGoing = true;
    if (result != nullptr) {
        // Boxed Boolean result.
        jclass boolClass = env->FindClass("java/lang/Boolean");
        jmethodID boolValue = env->GetMethodID(boolClass, "booleanValue", "()Z");
        keepGoing = env->CallBooleanMethod(result, boolValue);
        env->DeleteLocalRef(result);
    }
    return keepGoing;
}

jmethodID getLambdaInvokeId(JNIEnv *env) {
    jclass fnClass = env->FindClass("kotlin/jvm/functions/Function1");
    if (fnClass == nullptr) return nullptr;
    jmethodID id = env->GetMethodID(fnClass, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    env->DeleteLocalRef(fnClass);
    return id;
}

struct SamplerSpec {
    float temperature = 0.7f;
    float topP = 0.9f;
    int topK = 40;
    float minP = 0.05f;
    float repeatPenalty = 1.1f;
    int seed = -1;
};

llama_sampler *buildSampler(const SamplerSpec &spec, int32_t contextTokens) {
    llama_sampler *chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    const int32_t penaltyLastN = spec.repeatPenalty > 1.0f ? std::min<int32_t>(64, std::max<int32_t>(16, contextTokens / 4)) : 0;
    if (penaltyLastN > 0) {
        // b4755 signature: (penalty_last_n, penalty_repeat, penalty_freq, penalty_present)
        llama_sampler_chain_add(chain, llama_sampler_init_penalties(penaltyLastN, spec.repeatPenalty, 0.0f, 0.0f));
    }
    if (spec.topK > 0) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(spec.topK));
    }
    if (spec.topP < 1.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(spec.topP, 1));
    }
    if (spec.minP > 0.0f) {
        llama_sampler_chain_add(chain, llama_sampler_init_min_p(spec.minP, 1));
    }
    llama_sampler_chain_add(chain, llama_sampler_init_temp(spec.temperature));
    const uint32_t seed = spec.seed >= 0 ? static_cast<uint32_t>(spec.seed) : static_cast<uint32_t>(0xFFFFFFFFu);
    llama_sampler_chain_add(chain, llama_sampler_init_dist(seed));
    return chain;
}

std::vector<llama_token> tokenizeText(const llama_vocab *vocab, const std::string &text) {
    const auto needed = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, true, true);
    std::vector<llama_token> tokens(needed > 0 ? needed : 0);
    if (needed > 0) {
        llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    }
    return tokens;
}

// Formats a conversation with the model's own chat template.
std::string applyChatTemplate(llama_model *model, const std::vector<llama_chat_message> &messages) {
    if (messages.empty()) return {};
    const int32_t conservativeSize = 2 * 1024 * 1024; // 2 MiB working buffer, grown if needed.
    std::vector<char> buf(static_cast<size_t>(conservativeSize));
    int32_t written = llama_chat_apply_template(nullptr, messages.data(), messages.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    if (written > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(written) + 1);
        written = llama_chat_apply_template(nullptr, messages.data(), messages.size(), true, buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (written <= 0) {
        // Honest fallback: plain concatenation when the model has no usable template.
        std::string plain;
        for (const auto &m : messages) {
            plain += std::string(m.role) + ": " + std::string(m.content) + "\n";
        }
        plain += "assistant: ";
        return plain;
    }
    return std::string(buf.data(), static_cast<size_t>(written));
}

std::vector<llama_chat_message> toChatMessages(JNIEnv *env, jobjectArray roles, jobjectArray contents) {
    std::vector<llama_chat_message> out;
    const jsize n = env->GetArrayLength(roles);
    if (n != env->GetArrayLength(contents)) return out;
    out.reserve(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto *role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto *content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        std::string roleStr = toStdString(env, role);
        std::string contentStr = toStdString(env, content);
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
        // Keep copies alive for the lifetime of the vector.
        char *r = new char[roleStr.size() + 1];
        char *c = new char[contentStr.size() + 1];
        std::copy(roleStr.begin(), roleStr.end(), r);
        std::copy(contentStr.begin(), contentStr.end(), c);
        r[roleStr.size()] = '\0';
        c[contentStr.size()] = '\0';
        out.push_back(llama_chat_message{r, c});
    }
    return out;
}

void freeChatMessages(std::vector<llama_chat_message> &messages) {
    for (auto &m : messages) {
        delete[] m.role;
        delete[] m.content;
        m.role = nullptr;
        m.content = nullptr;
    }
}

} // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_localai_runtime_core_runtime_cpu_LlamaBridge_nativeInit(JNIEnv * /*env*/, jobject /*thiz*/) {
    llama_backend_init();
    llama_log_set(llamaLogCallback, nullptr);
    LOGI("llama.cpp initialised (bridge target b4755)");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_localai_runtime_core_runtime_cpu_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jobject /*thiz*/, jstring path, jint nCtx, jint nThreads, jboolean useMmap) {
    const std::string modelPath = toStdString(env, path);
    if (modelPath.empty()) return 0;

    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = useMmap == JNI_TRUE;

    llama_model *model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (model == nullptr) {
        LOGE("Failed to load model: %s", modelPath.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(nCtx > 0 ? nCtx : 4096);
    cparams.n_seq_max = 1;
    const int32_t threads = nThreads > 0 ? nThreads : static_cast<int32_t>(std::thread::hardware_concurrency());
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        LOGE("Failed to create context for %s", modelPath.c_str());
        return 0;
    }

    auto *bridge = new BridgeModel();
    bridge->model = model;
    bridge->ctx = ctx;
    LOGI("Model loaded: %s (ctx=%d threads=%d)", modelPath.c_str(), (int) cparams.n_ctx, threads);
    return reinterpret_cast<jlong>(bridge);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localai_runtime_core_runtime_cpu_LlamaBridge_nativeFreeModel(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong modelPtr) {
    auto *bridge = reinterpret_cast<BridgeModel *>(modelPtr);
    if (bridge == nullptr) return;
    std::lock_guard<std::mutex> lock(bridge->mutex);
    if (bridge->ctx != nullptr) llama_free(bridge->ctx);
    if (bridge->model != nullptr) llama_model_free(bridge->model);
    bridge->ctx = nullptr;
    bridge->model = nullptr;
    delete bridge;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localai_runtime_core_runtime_cpu_LlamaBridge_nativeContextLength(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong modelPtr) {
    auto *bridge = reinterpret_cast<BridgeModel *>(modelPtr);
    if (bridge == nullptr || bridge->ctx == nullptr) return 0;
    std::lock_guard<std::mutex> lock(bridge->mutex);
    const int32_t nCtx = llama_n_ctx(bridge->ctx);
    return nCtx;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_localai_runtime_core_runtime_cpu_LlamaBridge_nativeTokenizeCount(
        JNIEnv *env, jobject /*thiz*/, jlong modelPtr, jstring text) {
    auto *bridge = reinterpret_cast<BridgeModel *>(modelPtr);
    if (bridge == nullptr || bridge->model == nullptr) return -1;
    std::lock_guard<std::mutex> lock(bridge->mutex);
    const llama_vocab *vocab = llama_model_get_vocab(bridge->model);
    const std::string s = toStdString(env, text);
    const auto needed = llama_tokenize(vocab, s.c_str(), static_cast<int32_t>(s.size()), nullptr, 0, true, true);
    return needed;
}

extern "C" JNIEXPORT void JNICALL
Java_com_localai_runtime_core_runtime_cpu_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject /*thiz*/, jlong modelPtr,
        jobjectArray roles, jobjectArray contents,
        jfloat temperature, jfloat topP, jint topK, jfloat minP, jfloat repeatPenalty,
        jint seed, jint maxTokens,
        jobject callback) {
    auto *bridge = reinterpret_cast<BridgeModel *>(modelPtr);
    if (bridge == nullptr || bridge->ctx == nullptr || bridge->model == nullptr) {
        return;
    }

    std::lock_guard<std::mutex> lock(bridge->mutex);

    const llama_vocab *vocab = llama_model_get_vocab(bridge->model);

    std::vector<llama_chat_message> messages = toChatMessages(env, roles, contents);
    const std::string prompt = applyChatTemplate(bridge->model, messages);
    freeChatMessages(messages);
    if (prompt.empty()) return;

    std::vector<llama_token> tokens = tokenizeText(vocab, prompt);
    if (tokens.empty()) {
        LOGW("Prompt tokenization produced no tokens");
        return;
    }

    SamplerSpec spec;
    spec.temperature = temperature;
    spec.topP = topP;
    spec.topK = topK;
    spec.minP = minP;
    spec.repeatPenalty = repeatPenalty;
    spec.seed = seed;
    llama_sampler *sampler = buildSampler(spec, llama_n_ctx(bridge->ctx));

    jmethodID invokeId = getLambdaInvokeId(env);

    char pieceBuf[512];
    int generated = 0;
    const int limit = maxTokens > 0 ? maxTokens : 512;

    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));

    // Prompt processing (first decode). Emits nothing but is timed by the stats layer.
    if (llama_decode(bridge->ctx, batch) != 0) {
        LOGE("Prompt decode failed");
        llama_sampler_free(sampler);
        return;
    }

    while (generated < limit) {
        const llama_token token = llama_sampler_sample(sampler, bridge->ctx, -1);
        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }
        const int32_t n = llama_token_to_piece(vocab, token, pieceBuf, sizeof(pieceBuf), 0, true);
        if (n > 0) {
            const std::string piece(pieceBuf, static_cast<size_t>(n));
            if (!callKotlinCallback(env, callback, invokeId, piece)) {
                break; // Consumer cancelled generation.
            }
        }
        batch = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
        if (llama_decode(bridge->ctx, batch) != 0) {
            LOGE("Decode failed during generation");
            break;
        }
        ++generated;
    }

    llama_sampler_free(sampler);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_localai_runtime_core_runtime_cpu_LlamaBridge_nativeBenchmark(
        JNIEnv *env, jobject /*thiz*/, jlong modelPtr, jint nPromptTokens, jint nGenTokens) {
    auto *bridge = reinterpret_cast<BridgeModel *>(modelPtr);
    if (bridge == nullptr || bridge->ctx == nullptr || bridge->model == nullptr) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(bridge->mutex);

    const llama_vocab *vocab = llama_model_get_vocab(bridge->model);
    const int32_t nVocab = llama_vocab_n_tokens(vocab);

    const int32_t nPrompt = nPromptTokens > 0 ? nPromptTokens : 512;
    const int32_t nGen = nGenTokens > 0 ? nGenTokens : 256;

    // Deterministic pseudo tokens for prompt processing timing.
    std::vector<llama_token> promptTokens;
    promptTokens.reserve(static_cast<size_t>(nPrompt));
    const llama_token bos = llama_vocab_bos(vocab);
    for (int32_t i = 0; i < nPrompt; ++i) {
        promptTokens.push_back(bos + (i % std::max(1, nVocab - 4)));
    }

    llama_kv_cache_clear(bridge->ctx);
    llama_sampler *sampler = buildSampler(SamplerSpec{}, llama_n_ctx(bridge->ctx));

    const auto tPromptStart = std::chrono::steady_clock::now();
    // Split the prompt into batches to respect n_batch.
    const int32_t nBatch = 512;
    for (int32_t offset = 0; offset < nPrompt; offset += nBatch) {
        const int32_t count = std::min(nBatch, nPrompt - offset);
        llama_batch batch = llama_batch_get_one(promptTokens.data() + offset, count);
        if (llama_decode(bridge->ctx, batch) != 0) {
            llama_sampler_free(sampler);
            return nullptr;
        }
    }
    const auto tPromptEnd = std::chrono::steady_clock::now();

    llama_token token = llama_sampler_sample(sampler, bridge->ctx, -1);
    const auto tGenStart = std::chrono::steady_clock::now();
    for (int32_t i = 1; i < nGen; ++i) {
        llama_batch batch = llama_batch_get_one(&token, 1);
        if (llama_decode(bridge->ctx, batch) != 0) break;
        token = llama_sampler_sample(sampler, bridge->ctx, -1);
    }
    const auto tGenEnd = std::chrono::steady_clock::now();

    llama_sampler_free(sampler);
    llama_kv_cache_clear(bridge->ctx);

    const double promptMs = std::chrono::duration<double, std::milli>(tPromptEnd - tPromptStart).count();
    const double genMs = std::chrono::duration<double, std::milli>(tGenEnd - tGenStart).count();
    const double promptTps = promptMs > 0 ? (nPrompt / (promptMs / 1000.0)) : 0.0;
    const double genTps = genMs > 0 ? (nGen / (genMs / 1000.0)) : 0.0;

    std::vector<double> out = {promptTps, genTps, promptMs, genMs};
    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(out.size()));
    if (result != nullptr) {
        env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(out.size()), out.data());
    }
    return result;
}
