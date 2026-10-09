// JNI-мост к llama.cpp (v0.6.0, классический llama_batch + llama_decode API).
// Собирается вместе с llama.cpp в один libllama.so, см. CMakeLists.txt рядом.
//
// Контракт с Kotlin (JniLlamaBridge):
//   nativeInit(modelPath, nCtx, nThreads) -> handle (0 = ошибка -> Kotlin кинет Exception)
//   nativeGenerate(handle, prompt)        -> полный текст (стримминг в UI делает Kotlin)
//   nativeCancel(handle)                  -> просьба остановить генерацию
//   nativeFree(handle)                    -> освободить модель/контекст
//
// Промпт уже собран в ChatML на стороне Kotlin (PromptBuilder), здесь только инференс.
#include <jni.h>

#include <atomic>
#include <chrono>
#include <stdexcept>
#include <string>
#include <vector>

#include <android/log.h>

#include "llama.h"

namespace {

// llama.cpp пишет причину падения в свой лог (stderr на десктопе).
// На Android stderr не виден, поэтому переводим всё в logcat отдельным
// тегом: иначе "не открылось" без причины гадается по кофейной гуще.
static void bridge_log_callback(ggml_log_level level, const char* text, void*) {
    if (!text) {
        return;
    }
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default:                   prio = ANDROID_LOG_INFO;  break;
    }
    __android_log_write(prio, "llama-bridge", text);
}

inline void bridge_ensure_logging() {
    static bool logging_once = false;
    if (!logging_once) {
        llama_log_set(bridge_log_callback, nullptr);
        logging_once = true;
    }
}

struct Handle {
    llama_model*   model   = nullptr;
    llama_context* ctx     = nullptr;
    llama_sampler* sampler = nullptr;
    const llama_vocab* vocab = nullptr;
    std::atomic<bool> cancel{false};
    int n_ctx = 2048;
};

void throw_java(JNIEnv* env, const std::string& msg) {
    const jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls != nullptr) {
        env->ThrowNew(cls, msg.c_str());
    }
}

// Ручное заполнение батча (без зависимости от libcommon).
inline void batch_add(llama_batch& batch, llama_token tok, llama_pos pos, bool need_logit) {
    batch.token   [batch.n_tokens] = tok;
    batch.pos     [batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = 1;
    batch.seq_id  [batch.n_tokens][0] = 0;
    batch.logits  [batch.n_tokens] = need_logit ? 1 : 0;
    batch.n_tokens++;
}

llama_sampler* make_sampler(const llama_vocab* vocab) {
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler* s = llama_sampler_chain_init(sparams);
    // Chat-preset for Qwen3 / SmolLM3 small talk: penalties first, then
    // truncation, then temp. Temp 0.7 keeps small models lively; repeat
    // penalty kills "I am a helpful assistant" loops. Order matters.
    const int32_t n_vocab = vocab ? llama_vocab_n_tokens(vocab) : 0;
    if (n_vocab > 0) {
        llama_sampler_chain_add(s, llama_sampler_init_penalties(n_vocab, 64, 1.1f, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(s, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(s, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(s, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(s, llama_sampler_init_temp(0.7f));
    const uint32_t seed =
        static_cast<uint32_t>(std::chrono::steady_clock::now().time_since_epoch().count());
    llama_sampler_chain_add(s, llama_sampler_init_dist(seed));
    return s;
}

std::string generate_impl(Handle* h, const std::string& prompt) {
    llama_memory_clear(llama_get_memory(h->ctx), true);
    llama_sampler_reset(h->sampler);
    h->cancel.store(false);

    // 1. Токенизация (с BOS).
    const int n_max = h->n_ctx - 256; // запас под ответ
    std::vector<llama_token> toks(n_max + 8);
    const int n_tok = llama_tokenize(h->vocab, prompt.c_str(), (int)prompt.size(),
                                     toks.data(), (int)toks.size(), true, true);
    if (n_tok <= 0) {
        throw std::runtime_error("пустой промпт после токенизации");
    }
    // Если история не влезла — режем слева, оставляем хвост.
    int start = 0;
    if (n_tok > n_max) {
        start = n_tok - n_max;
    }
    const int n_prompt = n_tok - start;

    // 2. Прогон промпта чанками.
    llama_batch batch = llama_batch_init(512, 0, 1);
    int n_past = 0;
    for (int i = 0; i < n_prompt; i += 512) {
        const int n = std::min(512, n_prompt - i);
        batch.n_tokens = 0;
        for (int j = 0; j < n; j++) {
            batch_add(batch, toks[start + i + j], n_past + j, (i + j) == n_prompt - 1);
        }
        if (llama_decode(h->ctx, batch) != 0) {
            llama_batch_free(batch);
            throw std::runtime_error("llama_decode промпта провален (OOM?)");
        }
        n_past += n;
    }

    // 3. Генерация (256 токенов хватает на 1-4 предложения, быстрее на телефоне).
    std::string out;
    const int max_new = 256;
    char piece[64];
    for (int i = 0; i < max_new; i++) {
        if (h->cancel.load()) {
            break;
        }
        const llama_token id = llama_sampler_sample(h->sampler, h->ctx, -1);
        if (llama_vocab_is_eog(h->vocab, id)) {
            break;
        }
        const int n = llama_token_to_piece(h->vocab, id, piece, sizeof(piece), 0, true);
        if (n > 0) {
            out.append(piece, n);
        }
        batch.n_tokens = 0;
        batch_add(batch, id, n_past++, true);
        if (llama_decode(h->ctx, batch) != 0) {
            break; // контекст переполнен или OOM — отдаём что успели
        }
    }
    llama_batch_free(batch);
    return out;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_task2_llama_JniLlamaBridge_nativeInit(JNIEnv* env, jobject, jstring jPath, jint nCtx, jint nThreads) {
    static bool backend_once = false;
    if (!backend_once) {
        llama_backend_init();
        backend_once = true;
    }
    bridge_ensure_logging();
    const char* cpath = env->GetStringUTFChars(jPath, nullptr);
    const std::string path = cpath ? cpath : "";
    if (cpath) {
        env->ReleaseStringUTFChars(jPath, cpath);
    }

    auto* h = new (std::nothrow) Handle();
    if (!h) {
        throw_java(env, "OOM при создании handle");
        return 0;
    }
    h->n_ctx = nCtx > 0 ? nCtx : 2048;

    try {
        auto mparams = llama_model_default_params();
        mparams.n_gpu_layers = 0; // CPU на телефоне
        h->model = llama_model_load_from_file(path.c_str(), mparams);
        if (!h->model) {
            throw std::runtime_error("llama_model_load_from_file не открыл " + path);
        }
        h->vocab = llama_model_get_vocab(h->model);

        auto cparams = llama_context_default_params();
        cparams.n_ctx           = h->n_ctx;
        cparams.n_threads       = nThreads > 0 ? nThreads : 4;
        cparams.n_threads_batch = cparams.n_threads;
        h->ctx = llama_init_from_model(h->model, cparams);
        if (!h->ctx) {
            throw std::runtime_error("llama_init_from_model провален (не хватило RAM под ctx=" +
                                     std::to_string(h->n_ctx) + ")");
        }
        h->sampler = make_sampler(h->vocab);
        return reinterpret_cast<jlong>(h);
    } catch (const std::exception& e) {
        if (h->sampler) {
            llama_sampler_free(h->sampler);
        }
        if (h->ctx) {
            llama_free(h->ctx);
        }
        if (h->model) {
            llama_model_free(h->model);
        }
        delete h;
        throw_java(env, e.what());
        return 0;
    }
}

JNIEXPORT jstring JNICALL
Java_task2_llama_JniLlamaBridge_nativeGenerate(JNIEnv* env, jobject, jlong hptr, jstring jPrompt) {
    auto* h = reinterpret_cast<Handle*>(hptr);
    if (!h || !h->ctx) {
        throw_java(env, "модель не загружена");
        return nullptr;
    }
    const char* cprompt = env->GetStringUTFChars(jPrompt, nullptr);
    const std::string prompt = cprompt ? cprompt : "";
    if (cprompt) {
        env->ReleaseStringUTFChars(jPrompt, cprompt);
    }
    try {
        const std::string out = generate_impl(h, prompt);
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception& e) {
        throw_java(env, e.what());
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_task2_llama_JniLlamaBridge_nativeCancel(JNIEnv*, jobject, jlong hptr) {
    auto* h = reinterpret_cast<Handle*>(hptr);
    if (h) {
        h->cancel.store(true);
    }
}

JNIEXPORT void JNICALL
Java_task2_llama_JniLlamaBridge_nativeFree(JNIEnv*, jobject, jlong hptr) {
    auto* h = reinterpret_cast<Handle*>(hptr);
    if (!h) {
        return;
    }
    if (h->sampler) {
        llama_sampler_free(h->sampler);
    }
    if (h->ctx) {
        llama_free(h->ctx);
    }
    if (h->model) {
        llama_model_free(h->model);
    }
    delete h;
}

// --- Embedding-режим (task3): BERT-подобные GGUF, pooling MEAN. ---
// Отдельный хэндл: генеративная и эмбеддинг-модель не делят контекст.
// Контракт с Kotlin (JniEmbedBridge):
//   nativeEmbInit(modelPath, nThreads) -> handle (0 = ошибка)
//   nativeEmbDim(handle)                -> размерность вектора
//   nativeEmbEmbed(handle, text)       -> float[n_embd] (L2-норму делает Kotlin)
//   nativeEmbFree(handle)               -> освободить модель/контекст

struct EmbHandle {
    llama_model*   model = nullptr;
    llama_context* ctx   = nullptr;
    const llama_vocab* vocab = nullptr;
    int n_embd = 0;
    int n_ctx  = 512;
};

JNIEXPORT jlong JNICALL
Java_task3_llama_JniEmbedBridge_nativeEmbInit(JNIEnv* env, jobject, jstring jPath, jint nThreads) {
    static bool emb_backend_once = false;
    if (!emb_backend_once) {
        llama_backend_init();
        emb_backend_once = true;
    }
    bridge_ensure_logging();
    const char* cpath = env->GetStringUTFChars(jPath, nullptr);
    const std::string path = cpath ? cpath : "";
    if (cpath) {
        env->ReleaseStringUTFChars(jPath, cpath);
    }

    auto* h = new (std::nothrow) EmbHandle();
    if (!h) {
        throw_java(env, "OOM при создании emb handle");
        return 0;
    }

    try {
        auto mparams = llama_model_default_params();
        mparams.n_gpu_layers = 0; // CPU на телефоне
        h->model = llama_model_load_from_file(path.c_str(), mparams);
        if (!h->model) {
            throw std::runtime_error("llama_model_load_from_file не открыл " + path);
        }
        h->vocab = llama_model_get_vocab(h->model);
        h->n_embd = llama_model_n_embd(h->model);
        if (h->n_embd <= 0) {
            throw std::runtime_error("модель не отдала n_embd (не embedding GGUF?)");
        }

        auto cparams = llama_context_default_params();
        cparams.n_ctx           = h->n_ctx;
        cparams.n_batch         = h->n_ctx;
        cparams.n_ubatch        = h->n_ctx;
        cparams.n_threads       = nThreads > 0 ? nThreads : 4;
        cparams.n_threads_batch = cparams.n_threads;
        cparams.embeddings      = true;
        cparams.pooling_type    = LLAMA_POOLING_TYPE_MEAN;
        h->ctx = llama_init_from_model(h->model, cparams);
        if (!h->ctx) {
            throw std::runtime_error("llama_init_from_model (embed) провален (не хватило RAM?)");
        }
        llama_set_embeddings(h->ctx, true);
        return reinterpret_cast<jlong>(h);
    } catch (const std::exception& e) {
        if (h->ctx) {
            llama_free(h->ctx);
        }
        if (h->model) {
            llama_model_free(h->model);
        }
        delete h;
        throw_java(env, e.what());
        return 0;
    }
}

JNIEXPORT jint JNICALL
Java_task3_llama_JniEmbedBridge_nativeEmbDim(JNIEnv*, jobject, jlong hptr) {
    auto* h = reinterpret_cast<EmbHandle*>(hptr);
    return h ? h->n_embd : 0;
}

JNIEXPORT jfloatArray JNICALL
Java_task3_llama_JniEmbedBridge_nativeEmbEmbed(JNIEnv* env, jobject, jlong hptr, jstring jText) {
    auto* h = reinterpret_cast<EmbHandle*>(hptr);
    if (!h || !h->ctx) {
        throw_java(env, "embedding-модель не загружена");
        return nullptr;
    }
    const char* ctext = env->GetStringUTFChars(jText, nullptr);
    const std::string text = ctext ? ctext : "";
    if (ctext) {
        env->ReleaseStringUTFChars(jText, ctext);
    }
    if (text.empty()) {
        throw_java(env, "пустой текст для эмбеддинга");
        return nullptr;
    }
    try {
        llama_memory_clear(llama_get_memory(h->ctx), true);

        // Двухпроходная токенизация: сначала меряем длину большим буфером,
        // потом режем до контекста. Русские чанки (~1500 символов) дают
        // больше токенов, чем n_ctx=512, а тесный буфер llama_tokenize
        // роняет отрицательным кодом — раньше это выглядело как "пустой текст".
        std::vector<llama_token> toks(8192);
        const int n_tok = llama_tokenize(h->vocab, text.c_str(), (int)text.size(),
                                         toks.data(), (int)toks.size(), true, true);
        if (n_tok < 0) {
            throw std::runtime_error("текст длиннее 8192 токенов (" +
                                     std::to_string(text.size()) + " символов)");
        }
        if (n_tok == 0) {
            throw std::runtime_error("пустой текст после токенизации");
        }
        // Длинное режем с головы: начало секции обычно несёт тему,
        // а хвост без неё для поиска почти бесполезен.
        const int n = std::min(n_tok, h->n_ctx);

        llama_batch batch = llama_batch_init(n, 0, 1);
        batch.n_tokens = 0;
        for (int i = 0; i < n; i++) {
            batch_add(batch, toks[i], i, true);
        }
        if (llama_decode(h->ctx, batch) != 0) {
            llama_batch_free(batch);
            throw std::runtime_error("llama_decode (embed) провален (OOM?)");
        }
        float* e = llama_get_embeddings_seq(h->ctx, 0);
        if (!e) {
            llama_batch_free(batch);
            throw std::runtime_error("нет эмбеддингов (pooling не включился?)");
        }
        jfloatArray out = env->NewFloatArray(h->n_embd);
        if (!out) {
            llama_batch_free(batch);
            throw std::runtime_error("OOM под jfloatArray");
        }
        env->SetFloatArrayRegion(out, 0, h->n_embd, e);
        llama_batch_free(batch);
        return out;
    } catch (const std::exception& e) {
        throw_java(env, e.what());
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_task3_llama_JniEmbedBridge_nativeEmbFree(JNIEnv*, jobject, jlong hptr) {
    auto* h = reinterpret_cast<EmbHandle*>(hptr);
    if (!h) {
        return;
    }
    if (h->ctx) {
        llama_free(h->ctx);
    }
    if (h->model) {
        llama_model_free(h->model);
    }
    delete h;
}

} // extern "C"
