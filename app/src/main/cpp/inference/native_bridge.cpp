#include <jni.h>

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

// ---------------------------------------------------------------------------
// Offline AI native engine.
//
// Pinned llama.cpp release: b12558 (see TOOLCHAIN.md).
// NOTE: this revision deprecates llama_decode()/llama_batch_init() in favour of
// llama_batch_ext_*() + llama_process(). We use the current API.
//
// Design notes:
//  - Exactly one model may be loaded at a time (product requirement), so a
//    single global engine guarded by a mutex is sufficient.
//  - Generation runs on the caller's thread (a Kotlin coroutine dispatcher
//    thread, already attached to the JVM), which lets us stream tokens back
//    through a JNI callback without AttachCurrentThread.
//  - Cancellation is a lock-free atomic checked between tokens and wired into
//    ggml's abort callback so a long llama_process() call also stops promptly.
// ---------------------------------------------------------------------------

namespace {

constexpr const char * kLogTag = "OfflineAI-Native";

struct Engine {
    std::mutex mtx;

    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_batch_ext * batch = nullptr;
    llama_sampler * smpl = nullptr;
    const llama_vocab * vocab = nullptr;

    std::atomic<bool> cancel{false};
    std::string last_error;
    std::vector<llama_token> cached_tokens;
    uint32_t n_ctx = 0;
    int32_t n_threads = 0;
    int32_t n_threads_batch = 0;
    int64_t load_time_us = 0;
    bool backend_inited = false;
};

Engine g;

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

// ggml abort callback: return true to abort the in-flight evaluation.
bool abort_cb(void * data) {
    return static_cast<std::atomic<bool> *>(data)->load();
}

void set_error(const std::string & msg) {
    g.last_error = msg;
}

// Returns the byte length of the complete UTF-8 characters in [0, n).
// Used so we never hand Kotlin a byte array split mid-codepoint.
size_t utf8_complete(const char * s, size_t n) {
    size_t i = n;
    while (i > 0) {
        const unsigned char c = static_cast<unsigned char>(s[i - 1]);
        if ((c & 0xC0) != 0x80) {
            size_t len;
            if ((c & 0x80) == 0x00) {
                len = 1;
            } else if ((c & 0xE0) == 0xC0) {
                len = 2;
            } else if ((c & 0xF0) == 0xE0) {
                len = 3;
            } else if ((c & 0xF8) == 0xF0) {
                len = 4;
            } else {
                i--; // stray continuation/invalid byte, drop it
                continue;
            }
            return (i - 1 + len <= n) ? n : i - 1;
        }
        i--;
    }
    return 0;
}

void release_locked() {
    if (g.smpl) {
        llama_sampler_free(g.smpl);
        g.smpl = nullptr;
    }
    if (g.batch) {
        llama_batch_ext_free(g.batch);
        g.batch = nullptr;
    }
    if (g.ctx) {
        llama_free(g.ctx);
        g.ctx = nullptr;
    }
    if (g.model) {
        llama_model_free(g.model);
        g.model = nullptr;
    }
    g.vocab = nullptr;
    g.cached_tokens.clear();
    g.n_ctx = 0;
    g.n_threads = 0;
    g.n_threads_batch = 0;
    g.load_time_us = 0;
    g.cancel.store(false);
}

jstring jstr(JNIEnv * env, const std::string & s) {
    return env->NewStringUTF(s.c_str());
}

std::string to_std(JNIEnv * env, jstring s) {
    if (!s) {
        return {};
    }
    const char * chars = env->GetStringUTFChars(s, nullptr);
    if (!chars) {
        return {};
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Formats the chat messages with the model's own template, falling back to a
// plain "role: content" layout when the model ships no template.
std::string build_prompt(llama_model * model, const std::vector<std::string> & roles,
                         const std::vector<std::string> & contents) {
    const size_t n_msg = roles.size();

    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl && *tmpl) {
        std::vector<llama_chat_message> msgs(n_msg);
        // llama_chat_message only borrows the pointers: keep them alive here.
        std::vector<std::string> keep(n_msg * 2);
        for (size_t i = 0; i < n_msg; i++) {
            keep[i * 2]     = roles[i];
            keep[i * 2 + 1] = contents[i];
            msgs[i].role    = keep[i * 2].c_str();
            msgs[i].content = keep[i * 2 + 1].c_str();
        }

        const int32_t needed = llama_chat_apply_template(tmpl, msgs.data(), n_msg, true, nullptr, 0);
        if (needed > 0) {
            std::vector<char> buf(needed + 1, 0);
            const int32_t written = llama_chat_apply_template(tmpl, msgs.data(), n_msg, true, buf.data(), needed + 1);
            if (written > 0 && written <= needed) {
                return std::string(buf.data(), written);
            }
        }
    }

    // Fallback: simple, model-agnostic transcript.
    std::string out;
    for (size_t i = 0; i < n_msg; i++) {
        out += roles[i];
        out += ": ";
        out += contents[i];
        out += "\n";
    }
    out += "assistant:";
    return out;
}

} // namespace

// ---------------------------------------------------------------------------
// JNI surface
// ---------------------------------------------------------------------------

extern "C" {

// Milestone D smoke test: proves the toolchain and JNI boundary work.
JNIEXPORT jstring JNICALL
Java_com_soprso_dhurandharai_inference_NativeBridge_nativeVersion(
        JNIEnv * env, jobject) {
    return jstr(env, std::string("llama.cpp b12558 / offlineai-native/0.2.0"));
}

JNIEXPORT jint JNICALL
Java_com_soprso_dhurandharai_inference_NativeBridge_nativeAdd(
        JNIEnv *, jobject, jint a, jint b) {
    return a + b;
}

// Returns "" on success, otherwise a human-readable error.
// Only one model may be loaded at a time; an existing model is unloaded first.
JNIEXPORT jstring JNICALL
Java_com_soprso_dhurandharai_inference_NativeBridge_nativeLoad(
        JNIEnv * env, jobject, jstring jpath, jint n_ctx, jint n_threads) {
    std::lock_guard<std::mutex> lock(g.mtx);

    g.last_error.clear();

    const std::string path = to_std(env, jpath);
    if (path.empty()) {
        return jstr(env, "model path is empty");
    }

    release_locked();

    const int64_t t_load_start = now_us();
    if (!g.backend_inited) {
        llama_backend_init();
        g.backend_inited = true;
    }
    const int64_t t_backend_done = now_us();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0; // CPU-only: no GPU backend is compiled in
    mp.load_mode = LLAMA_LOAD_MODE_MMAP; // mmap on, mlock off

    g.model = llama_model_load_from_file(path.c_str(), mp);
    const int64_t t_model_done = now_us();
    if (!g.model) {
        set_error("failed to load model (file missing, corrupt, or out of memory)");
        return jstr(env, g.last_error);
    }

    g.vocab = llama_model_get_vocab(g.model);
    if (!g.vocab) {
        release_locked();
        set_error("model has no vocabulary");
        return jstr(env, g.last_error);
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx > 0 ? static_cast<uint32_t>(n_ctx) : 4096;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_seq_max = 1;
    cp.n_threads = n_threads > 0 ? n_threads : 4;
    cp.n_threads_batch = cp.n_threads;
    cp.no_perf = true; // avoid llama.cpp's internal timing overhead in production builds
    cp.abort_callback = abort_cb;
    cp.abort_callback_data = &g.cancel;

    g.ctx = llama_init_from_model(g.model, cp);
    const int64_t t_context_done = now_us();
    if (!g.ctx) {
        release_locked();
        set_error("failed to create context (context length too large for available memory?)");
        return jstr(env, g.last_error);
    }

    g.batch = llama_batch_ext_init(g.ctx);
    if (!g.batch) {
        release_locked();
        set_error("failed to allocate batch");
        return jstr(env, g.last_error);
    }

    const auto sparams = llama_sampler_chain_default_params();
    g.smpl = llama_sampler_chain_init(sparams);
    if (!g.smpl) {
        release_locked();
        set_error("failed to allocate sampler");
        return jstr(env, g.last_error);
    }

    g.n_ctx = cp.n_ctx;
    g.n_threads = cp.n_threads;
    g.n_threads_batch = cp.n_threads_batch;
    g.load_time_us = t_context_done - t_load_start;
    g.cancel.store(false);

    __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "load path=%s n_ctx=%u n_batch=%u n_ubatch=%u threads=%d batch_threads=%d backend_ms=%.1f model_ms=%.1f context_ms=%.1f total_ms=%.1f",
            path.c_str(), g.n_ctx, cp.n_batch, cp.n_ubatch, g.n_threads, g.n_threads_batch,
            (t_backend_done - t_load_start) / 1000.0,
            (t_model_done - t_backend_done) / 1000.0,
            (t_context_done - t_model_done) / 1000.0,
            g.load_time_us / 1000.0);
    return jstr(env, "");
}

JNIEXPORT void JNICALL
Java_com_soprso_dhurandharai_inference_NativeBridge_nativeUnload(
        JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g.mtx);
    release_locked();
}

JNIEXPORT jboolean JNICALL
Java_com_soprso_dhurandharai_inference_NativeBridge_nativeIsLoaded(
        JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g.mtx);
    return g.model != nullptr && g.ctx != nullptr;
}

JNIEXPORT jint JNICALL
Java_com_soprso_dhurandharai_inference_NativeBridge_nativeContextLength(
        JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g.mtx);
    return static_cast<jint>(g.n_ctx);
}

// Lock-free: must be callable while generate() holds the mutex.
JNIEXPORT void JNICALL
Java_com_soprso_dhurandharai_inference_NativeBridge_nativeCancel(
        JNIEnv *, jobject) {
    g.cancel.store(true);
}

JNIEXPORT jstring JNICALL
Java_com_soprso_dhurandharai_inference_NativeBridge_nativeLastError(
        JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g.mtx);
    return jstr(env, g.last_error);
}

JNIEXPORT jstring JNICALL
Java_com_soprso_dhurandharai_inference_NativeBridge_nativeSetThreads(
        JNIEnv * env, jobject, jint n_threads, jint n_threads_batch) {
    std::lock_guard<std::mutex> lock(g.mtx);
    g.last_error.clear();
    if (!g.ctx) {
        set_error("no model loaded");
        return jstr(env, g.last_error);
    }
    const int32_t decode_threads = n_threads > 0 ? n_threads : 4;
    const int32_t batch_threads = n_threads_batch > 0 ? n_threads_batch : decode_threads;
    llama_set_n_threads(g.ctx, decode_threads, batch_threads);
    g.n_threads = decode_threads;
    g.n_threads_batch = batch_threads;
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
            "threads decode=%d batch=%d", decode_threads, batch_threads);
    return jstr(env, "");
}

// messages: flat [role, content, role, content, ...]
// listener: com.soprso.dhurandharai.inference.TokenListener
// metricsListener: com.soprso.dhurandharai.inference.MetricsListener
// Returns the full generated text, or null on error (see nativeLastError()).
// A cancelled generation returns the partial text produced so far.
JNIEXPORT jstring JNICALL
Java_com_soprso_dhurandharai_inference_NativeBridge_nativeGenerate(
        JNIEnv * env, jobject,
        jobjectArray jmessages,
        jint max_tokens,
        jfloat temperature,
        jint top_k,
        jfloat top_p,
        jfloat min_p,
        jlong seed,
        jobject listener,
        jobject metricsListener) {

    std::lock_guard<std::mutex> lock(g.mtx);

    const int64_t t_request_start = now_us();
    int32_t prompt_chars = 0;
    int32_t prompt_tokens = 0;
    int64_t format_us = 0;
    int64_t tokenize_us = 0;
    int64_t prompt_process_us = 0;
    int64_t first_token_us = -1;
    int64_t generation_us = 0;
    int32_t prompt_processed_tokens = 0;
    int32_t prompt_reused_tokens = 0;
    int32_t generated_tokens = 0;
    int32_t result_code = -99;

    g.last_error.clear();

    if (!g.model || !g.ctx || !g.batch || !g.smpl || !g.vocab) {
        set_error("no model loaded");
        return nullptr;
    }

    // ---- read messages ----------------------------------------------------
    const jsize n_flat = jmessages ? env->GetArrayLength(jmessages) : 0;
    if (n_flat < 2 || (n_flat % 2) != 0) {
        set_error("conversation must contain at least one user message");
        return nullptr;
    }

    std::vector<std::string> roles, contents;
    roles.reserve(n_flat / 2);
    contents.reserve(n_flat / 2);
    for (jsize i = 0; i + 1 < n_flat; i += 2) {
        auto * r = static_cast<jstring>(env->GetObjectArrayElement(jmessages, i));
        auto * c = static_cast<jstring>(env->GetObjectArrayElement(jmessages, i + 1));
        roles.push_back(to_std(env, r));
        contents.push_back(to_std(env, c));
        if (r) env->DeleteLocalRef(r);
        if (c) env->DeleteLocalRef(c);
    }

    // ---- tokenize ---------------------------------------------------------
    const int64_t t_format_start = now_us();
    const std::string prompt = build_prompt(g.model, roles, contents);
    const int64_t t_tokenize_start = now_us();

    const int32_t n_prompt =
        -llama_tokenize(g.vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                        nullptr, 0, true, true);
    if (n_prompt <= 0) {
        set_error("failed to tokenize prompt");
        return nullptr;
    }

    const int32_t limit = max_tokens > 0 ? max_tokens : 512;
    if (static_cast<uint64_t>(n_prompt) + static_cast<uint64_t>(limit) > g.n_ctx) {
        set_error("prompt plus requested reply exceeds the context length (" +
                  std::to_string(g.n_ctx) + " tokens)");
        return nullptr;
    }

    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(g.vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                       tokens.data(), n_prompt, true, true) < 0) {
        set_error("failed to tokenize prompt");
        return nullptr;
    }
    const int64_t t_tokenize_done = now_us();
    prompt_chars = static_cast<int32_t>(prompt.size());
    prompt_tokens = n_prompt;
    format_us = t_tokenize_start - t_format_start;
    tokenize_us = t_tokenize_done - t_tokenize_start;

    // ---- sampler ----------------------------------------------------------
    llama_sampler_free(g.smpl);
    g.smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!g.smpl) {
        set_error("failed to allocate sampler");
        return nullptr;
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(g.smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0) {
            llama_sampler_chain_add(g.smpl, llama_sampler_init_top_k(top_k));
        }
        if (top_p < 1.0f) {
            llama_sampler_chain_add(g.smpl, llama_sampler_init_top_p(top_p, 1));
        }
        if (min_p > 0.0f) {
            llama_sampler_chain_add(g.smpl, llama_sampler_init_min_p(min_p, 1));
        }
        llama_sampler_chain_add(g.smpl, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(g.smpl, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    // ---- callback lookup --------------------------------------------------
    jclass listener_cls = listener ? env->GetObjectClass(listener) : nullptr;
    jmethodID on_token = listener_cls
        ? env->GetMethodID(listener_cls, "onToken", "([B)V")
        : nullptr;
    if (listener && (!on_token || env->ExceptionCheck())) {
        env->ExceptionClear();
        set_error("TokenListener must declare fun onToken(bytes: ByteArray)");
        return nullptr;
    }

    jclass metrics_cls = metricsListener ? env->GetObjectClass(metricsListener) : nullptr;
    jmethodID on_metrics = metrics_cls
        ? env->GetMethodID(metrics_cls, "onMetrics", "([J)V")
        : nullptr;
    if (metricsListener && (!on_metrics || env->ExceptionCheck())) {
        env->ExceptionClear();
        set_error("MetricsListener must declare fun onMetrics(values: LongArray)");
        return nullptr;
    }

    auto report = [&](int32_t code, bool cancelled) {
        const int64_t total_us = now_us() - t_request_start;
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                "generate prompt_chars=%d prompt_tokens=%d processed=%d reused=%d n_ctx=%u threads=%d batch_threads=%d "
                "format_ms=%.1f tokenize_ms=%.1f prompt_ms=%.1f prompt_tps=%.2f first_token_ms=%.1f "
                "gen_tokens=%d gen_ms=%.1f gen_tps=%.2f total_ms=%.1f rc=%d cancelled=%d error=%s",
                prompt_chars, prompt_tokens, prompt_processed_tokens, prompt_reused_tokens,
                g.n_ctx, g.n_threads, g.n_threads_batch,
                format_us / 1000.0, tokenize_us / 1000.0, prompt_process_us / 1000.0,
                prompt_process_us > 0 ? 1e6 * prompt_processed_tokens / prompt_process_us : 0.0,
                first_token_us / 1000.0, generated_tokens, generation_us / 1000.0,
                generation_us > 0 ? 1e6 * generated_tokens / generation_us : 0.0,
                total_us / 1000.0, code, cancelled ? 1 : 0, g.last_error.c_str());

        if (!on_metrics) {
            return;
        }
        const jlong values[] = {
            static_cast<jlong>(g.load_time_us),
            static_cast<jlong>(prompt_chars),
            static_cast<jlong>(prompt_tokens),
            static_cast<jlong>(g.n_ctx),
            static_cast<jlong>(format_us),
            static_cast<jlong>(tokenize_us),
            static_cast<jlong>(prompt_process_us),
            static_cast<jlong>(first_token_us),
            static_cast<jlong>(generation_us),
            static_cast<jlong>(generated_tokens),
            static_cast<jlong>(total_us),
            static_cast<jlong>(code),
            static_cast<jlong>(cancelled ? 1 : 0),
            static_cast<jlong>(g.n_threads),
            static_cast<jlong>(g.n_threads_batch),
            static_cast<jlong>(prompt_processed_tokens),
            static_cast<jlong>(prompt_reused_tokens),
        };
        jlongArray arr = env->NewLongArray(static_cast<jsize>(sizeof(values) / sizeof(values[0])));
        if (!arr) {
            return;
        }
        env->SetLongArrayRegion(arr, 0, static_cast<jsize>(sizeof(values) / sizeof(values[0])), values);
        env->CallVoidMethod(metricsListener, on_metrics, arr);
        env->DeleteLocalRef(arr);
    };

    auto cleanup_refs = [&]() {
        if (listener_cls) {
            env->DeleteLocalRef(listener_cls);
        }
        if (metrics_cls) {
            env->DeleteLocalRef(metrics_cls);
        }
    };

    g.cancel.store(false);

    auto emit = [&](const std::string & bytes) {
        if (!on_token || bytes.empty()) {
            return;
        }
        if (first_token_us < 0) {
            first_token_us = now_us() - t_request_start;
        }
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (!arr) {
            return;
        }
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(bytes.size()),
                                reinterpret_cast<const jbyte *>(bytes.data()));
        env->CallVoidMethod(listener, on_token, arr);
        env->DeleteLocalRef(arr);
    };

    // ---- prompt processing ------------------------------------------------
    auto fill_batch = [&](const llama_token * toks, int32_t n, llama_pos pos0, bool last_wants_logits) {
        llama_batch_ext_clear(g.batch);
        for (int32_t i = 0; i < n; i++) {
            const int32_t idx = llama_batch_ext_add_token(g.batch, 0, toks[i]);
            if (idx < 0) {
                return false;
            }
            const llama_pos p = pos0 + i;
            if (!llama_batch_ext_set_pos(g.batch, idx, &p)) {
                return false;
            }
        }
        if (last_wants_logits) {
            llama_batch_ext_set_output_logits(g.batch, n - 1, true);
        }
        return true;
    };

    auto invalidate_kv = [&]() {
        llama_memory_clear(llama_get_memory(g.ctx), false);
        g.cached_tokens.clear();
    };

    // Reuse the longest token prefix still present in the KV cache. At least
    // one new token must be evaluated so sampling has fresh logits.
    const size_t max_reuse = std::min<size_t>(
        g.cached_tokens.size(), static_cast<size_t>(std::max(0, n_prompt - 1)));
    size_t reuse = 0;
    while (reuse < max_reuse && tokens[reuse] == g.cached_tokens[reuse]) {
        reuse++;
    }
    llama_memory_t mem = llama_get_memory(g.ctx);
    if (reuse > 0) {
        const llama_pos pos_min = llama_memory_seq_pos_min(mem, 0);
        const llama_pos pos_max = llama_memory_seq_pos_max(mem, 0);
        if (pos_min != 0 || pos_max + 1 < static_cast<llama_pos>(reuse) ||
            !llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(reuse), -1)) {
            invalidate_kv();
            reuse = 0;
        } else {
            g.cached_tokens.resize(reuse);
        }
    } else {
        invalidate_kv();
    }
    prompt_reused_tokens = static_cast<int32_t>(reuse);

    constexpr int32_t kPromptChunk = 128;
    llama_pos n_past = static_cast<llama_pos>(reuse);
    const int64_t t_prompt_start = now_us();
    while (n_past < n_prompt) {
        if (g.cancel.load()) {
            invalidate_kv();
            report(-96, true);
            cleanup_refs();
            return jstr(env, "");
        }
        const int32_t chunk = std::min(kPromptChunk, n_prompt - n_past);
        const bool last_chunk = (n_past + chunk == n_prompt);
        if (!fill_batch(tokens.data() + n_past, chunk, n_past, last_chunk)) {
            invalidate_kv();
            set_error("failed to fill prompt batch");
            report(-98, false);
            cleanup_refs();
            return nullptr;
        }
        result_code = llama_process(g.ctx, LLAMA_PROCESS_TYPE_DECODE, g.batch);
        if (g.cancel.load()) {
            invalidate_kv();
            report(result_code, true);
            cleanup_refs();
            return jstr(env, "");
        }
        if (result_code != 0) {
            invalidate_kv();
            set_error("prompt processing failed (code " + std::to_string(result_code) + ")");
            report(result_code, false);
            cleanup_refs();
            return nullptr;
        }
        for (int32_t i = 0; i < chunk; i++) {
            g.cached_tokens.push_back(tokens[n_past + i]);
        }
        n_past += chunk;
        prompt_processed_tokens += chunk;
    }
    prompt_process_us = now_us() - t_prompt_start;

    std::string output;
    std::string pending; // incomplete UTF-8 tail, held back from the listener

    output.reserve(static_cast<size_t>(limit) * 8 + 64);
    pending.reserve(16);
    const int64_t t_generation_start = now_us();

    for (int32_t i = 0; i < limit; i++) {
        if (g.cancel.load()) {
            invalidate_kv();
            break;
        }

        llama_token next = llama_sampler_sample(g.smpl, g.ctx, -1);
        if (llama_vocab_is_eog(g.vocab, next)) {
            break;
        }
        generated_tokens++;

        char buf[512];
        const int32_t n = llama_token_to_piece(g.vocab, next, buf, sizeof(buf), 0, false);
        if (n > 0) {
            pending.append(buf, n);
            const size_t complete = utf8_complete(pending.data(), pending.size());
            if (complete > 0) {
                output.append(pending, 0, complete);
                emit(pending.substr(0, complete));
                pending.erase(0, complete);
            }
        }

        // Feed the sampled token back for the next step.
        if (!fill_batch(&next, 1, n_past, true)) {
            invalidate_kv();
            set_error("failed to fill decode batch");
            result_code = -97;
            break;
        }
        result_code = llama_process(g.ctx, LLAMA_PROCESS_TYPE_DECODE, g.batch);
        if (result_code != 0) {
            invalidate_kv();
            if (g.cancel.load()) {
                break;
            }
            set_error("generation failed (code " + std::to_string(result_code) + ")");
            break;
        }
        g.cached_tokens.push_back(next);
        n_past++;
    }

    generation_us = now_us() - t_generation_start;

    const bool cancelled = g.cancel.load();
    if (cancelled || !g.last_error.empty()) {
        invalidate_kv();
    }

    // Flush any bytes held back (normally nothing).
    if (!pending.empty() && !cancelled) {
        output.append(pending);
        emit(pending);
    }

    report(result_code, cancelled);
    cleanup_refs();

    if (!g.last_error.empty()) {
        return nullptr;
    }

    return jstr(env, output);
}

} // extern "C"
