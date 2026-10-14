/*
 * Jarvis Revived — native LLM bridge (llama.cpp ↔ Java).
 *
 * Ported from the old assistant.cpp with the Patch 19 fixes:
 *  - every string to Java goes UTF-8 → UTF-16 → NewString (no NewStringUTF crash
 *    on emoji / split multi-byte characters)
 *  - chat mode stops at real sentence ends only ("3.14", "Dr. Rao" stay intact)
 *  - mild repetition penalty in chat mode, pure greedy in JSON mode
 *  - KV cache cleared after a failed decode; context-full guard
 *
 * Changes vs the old bridge: generation mode and sentence budget are explicit
 * arguments (no more sniffing the prompt text for "JSON"), and context size /
 * thread count are chosen by Java at load time.
 */
#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdint>
#include <atomic>
#include <mutex>
#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <android/log.h>

#include "llama.h"

#define LOG_TAG "JarvisLLM"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

    static llama_model * g_model = nullptr;
    static llama_context * g_ctx = nullptr;
    static const llama_vocab * g_vocab = nullptr;

    static std::string g_model_path;
    static std::vector<llama_token> g_prompt_tokens;
    static std::atomic<bool> g_abort_requested(false);
    static std::atomic<bool> g_backend_initialized(false);
    static std::mutex g_state_mutex;

    // Timing of the last generation, shown in the app so speed can be tuned on the phone.
    static int32_t g_stat_prompt_tokens = 0;
    static int32_t g_stat_reused_tokens = 0;
    static int64_t g_stat_prompt_ms = 0;
    static int32_t g_stat_gen_tokens = 0;
    static int64_t g_stat_gen_ms = 0;

    int64_t now_ms() {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
    }

    static int32_t g_ctx_size = 1024;
    static int32_t g_threads  = 4;

    constexpr int32_t kBatchSize        = 128;
    constexpr int32_t kDefaultNewTokens = 64;
    constexpr int32_t kMaxNewTokensCap  = 512;

    // Must match LlamaBridge.MODE_* in Java.
    constexpr int kModeChat = 0;   // stop after N complete sentences
    constexpr int kModeJson = 1;   // stop once a {...} object is closed
    constexpr int kModeFree = 2;   // run until EOG / token budget

    constexpr bool kAddBos       = true;
    constexpr bool kParseSpecial = true;

    std::string trim_copy(const std::string &s) {
        size_t start = 0;
        while (start < s.size() && std::isspace(static_cast<unsigned char>(s[start]))) {
            start++;
        }

        size_t end = s.size();
        while (end > start && std::isspace(static_cast<unsigned char>(s[end - 1]))) {
            end--;
        }

        return s.substr(start, end - start);
    }

    bool looks_like_weak_chunk(const std::string &chunk) {
        std::string s = trim_copy(chunk);
        if (s.empty()) return true;
        if (s.size() < 8) return true;

        int alphaCount = 0;
        for (char c : s) {
            if (std::isalpha(static_cast<unsigned char>(c))) {
                alphaCount++;
            }
        }

        if (alphaCount < 4) return true;

        std::string lower = s;
        std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) {
            return static_cast<char>(std::tolower(c));
        });

        return lower == "and"
               || lower == ", and"
               || lower == "or"
               || lower == ", or"
               || lower == "but"
               || lower == ", but"
               || lower == "the"
               || lower == "a"
               || lower == "an";
    }

    /*
     * JNI NewStringUTF() expects *Modified* UTF-8. Real model output is standard
     * UTF-8, so emoji (4-byte sequences) or a multi-byte character split across
     * two tokens makes NewStringUTF abort the process under CheckJNI (debug
     * builds) or produce garbage in release. Every string handed to Java goes
     * through make_jstring_utf8(), which decodes UTF-8 -> UTF-16 itself and uses
     * NewString(). Invalid bytes become U+FFFD instead of crashing.
     */
    size_t utf8_complete_prefix_len(const std::string &s) {
        const size_t n = s.size();
        if (n == 0) return 0;

        // Walk back over at most 3 continuation bytes to find the lead byte.
        size_t i = n;
        int continuation = 0;
        while (i > 0 && continuation < 3) {
            const unsigned char c = static_cast<unsigned char>(s[i - 1]);
            if ((c & 0xC0) != 0x80) break;
            --i;
            ++continuation;
        }
        if (i == 0) return n;  // only continuation bytes; let the decoder replace them

        const unsigned char lead = static_cast<unsigned char>(s[i - 1]);
        size_t need = 1;
        if ((lead & 0x80) == 0x00) need = 1;
        else if ((lead & 0xE0) == 0xC0) need = 2;
        else if ((lead & 0xF0) == 0xE0) need = 3;
        else if ((lead & 0xF8) == 0xF0) need = 4;
        else return n;  // invalid lead byte; decoder will replace it

        const size_t have = static_cast<size_t>(continuation) + 1;
        return have < need ? (i - 1) : n;
    }

    std::vector<jchar> utf8_to_utf16(const std::string &s) {
        std::vector<jchar> out;
        out.reserve(s.size());

        const size_t n = s.size();
        size_t i = 0;
        while (i < n) {
            const unsigned char c = static_cast<unsigned char>(s[i]);
            uint32_t cp = 0xFFFD;
            size_t len = 1;

            if (c < 0x80) {
                cp = c;
            } else if ((c & 0xE0) == 0xC0) {
                len = 2;
            } else if ((c & 0xF0) == 0xE0) {
                len = 3;
            } else if ((c & 0xF8) == 0xF0) {
                len = 4;
            }

            if (len > 1) {
                bool ok = (i + len <= n);
                uint32_t v = (len == 2) ? (c & 0x1F) : (len == 3) ? (c & 0x0F) : (c & 0x07);
                for (size_t k = 1; ok && k < len; ++k) {
                    const unsigned char cc = static_cast<unsigned char>(s[i + k]);
                    if ((cc & 0xC0) != 0x80) { ok = false; break; }
                    v = (v << 6) | (cc & 0x3F);
                }
                // Reject overlong forms, surrogates and out-of-range values.
                if (ok && ((len == 2 && v < 0x80) || (len == 3 && v < 0x800) ||
                           (len == 4 && (v < 0x10000 || v > 0x10FFFF)) ||
                           (v >= 0xD800 && v <= 0xDFFF))) {
                    ok = false;
                }
                if (ok) {
                    cp = v;
                } else {
                    cp = 0xFFFD;
                    len = 1;
                }
            }

            if (cp >= 0x10000) {
                cp -= 0x10000;
                out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
                out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
            } else {
                out.push_back(static_cast<jchar>(cp));
            }
            i += len;
        }

        return out;
    }

    jstring make_jstring_utf8(JNIEnv *env, const std::string &s) {
        const std::vector<jchar> utf16 = utf8_to_utf16(s);
        return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
    }

    /*
     * True when the text ends a sentence: ., ? or ! that is not part of a common
     * abbreviation ("Dr.", "e.g.") or a single-letter initial ("J.").
     * Decimals are handled by the caller: it only stops once the NEXT piece
     * starts with whitespace, so "25." followed by "5" keeps going.
     */
    bool ends_sentence(const std::string &text) {
        const std::string s = trim_copy(text);
        if (s.empty()) return false;

        const char last = s.back();
        if (last == '?' || last == '!') return true;
        if (last != '.') return false;

        size_t end = s.size() - 1;
        size_t start = end;
        while (start > 0 && !std::isspace(static_cast<unsigned char>(s[start - 1]))) {
            --start;
        }

        std::string word = s.substr(start, end - start);
        std::transform(word.begin(), word.end(), word.begin(), [](unsigned char c) {
            return static_cast<char>(std::tolower(c));
        });
        while (!word.empty() && (word.front() == '(' || word.front() == '"' || word.front() == '\'')) {
            word.erase(word.begin());
        }

        if (word.size() == 1 && std::isalpha(static_cast<unsigned char>(word[0]))) {
            return false;  // initial, e.g. "J. K. Rowling"
        }

        static const char *kAbbrev[] = {
                "mr", "mrs", "ms", "dr", "st", "sr", "jr", "prof", "vs", "etc",
                "e.g", "i.e", "approx", "no", "fig", "dept", "govt", "rs", "inc", "ltd"
        };
        for (const char *a : kAbbrev) {
            if (word == a) return false;
        }
        return true;
    }

    bool piece_starts_with_space(const std::string &piece) {
        return !piece.empty() && std::isspace(static_cast<unsigned char>(piece[0]));
    }

    bool chunk_is_flushable(const std::string &chunk) {
        std::string s = trim_copy(chunk);
        if (looks_like_weak_chunk(s)) return false;

        const size_t len = s.size();
        const char last = s.empty() ? '\0' : s.back();

        if (last == '?' || last == '!' || last == ':' || last == '\n') {
            return true;
        }

        if (last == '.') {
            // "25." may be the start of "25.5" and "Dr." is not a sentence end:
            // hold those back until more text arrives (or the final flush).
            const bool after_digit = len >= 2 && std::isdigit(static_cast<unsigned char>(s[len - 2]));
            return !after_digit && ends_sentence(s);
        }

        return len >= 32;
    }


    void send_partial_callback(
            JNIEnv *env,
            jobject callback,
            jmethodID onPartialMethod,
            const std::string &chunk
    ) {
        if (env == nullptr || callback == nullptr || onPartialMethod == nullptr) return;
        if (chunk.empty()) return;

        jstring jChunk = make_jstring_utf8(env, chunk);
        if (jChunk == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            return;
        }

        env->CallVoidMethod(callback, onPartialMethod, jChunk);

        if (env->ExceptionCheck()) {
            LOGE("❌ Exception while sending partial callback");
            env->ExceptionDescribe();
            env->ExceptionClear();
        }

        env->DeleteLocalRef(jChunk);
    }

    void free_context_locked() {
        if (g_ctx != nullptr) {
            llama_free(g_ctx);
            g_ctx = nullptr;
        }
        g_prompt_tokens.clear();
    }

    void free_model_locked() {
        free_context_locked();

        if (g_model != nullptr) {
            llama_model_free(g_model);
            g_model = nullptr;
        }

        g_vocab = nullptr;
        g_model_path.clear();
    }

    bool ensure_backend_locked() {
        if (!g_backend_initialized.load()) {
            llama_backend_init();
            g_backend_initialized.store(true);
            LOGD("✅ llama backend initialized");
        }
        return true;
    }

    bool create_context_locked() {
        if (g_model == nullptr) {
            LOGE("❌ create_context_locked: model is null");
            return false;
        }

        if (g_ctx != nullptr) {
            return true;
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = static_cast<uint32_t>(g_ctx_size);
        cparams.n_batch = std::min<int32_t>(kBatchSize, g_ctx_size);

        // Galaxy A15 = 2x Cortex-A76 big + 6x Cortex-A55 little.
        // All 8 threads often wait on the little cores; 4 is a better default.
        cparams.n_threads = g_threads;
        cparams.n_threads_batch = g_threads;
       // cparams.flash_attn = true;

        g_ctx = llama_init_from_model(g_model, cparams);
        if (g_ctx == nullptr) {
            LOGE("❌ Context creation failed");
            return false;
        }

        g_prompt_tokens.clear();

        LOGD("✅ Context created (n_ctx=%d, n_batch=%d, n_threads=%d)",
             cparams.n_ctx, cparams.n_batch, cparams.n_threads);
        return true;
    }

    size_t common_prefix_len(const std::vector<llama_token> &a,
                             const std::vector<llama_token> &b) {
        const size_t n = std::min(a.size(), b.size());
        size_t i = 0;
        while (i < n && a[i] == b[i]) {
            ++i;
        }
        return i;
    }

    bool tokenize_prompt_locked(const std::string &text,
                                std::vector<llama_token> &out_tokens) {
        if (g_vocab == nullptr) {
            LOGE("❌ tokenize_prompt_locked: vocab is null");
            return false;
        }

        int needed = llama_tokenize(
                g_vocab,
                text.c_str(),
                static_cast<int32_t>(text.size()),
                nullptr,
                0,
                kAddBos,
                kParseSpecial
        );

        if (needed < 0) {
            needed = -needed;
        }

        if (needed <= 0) {
            LOGE("❌ Tokenization sizing failed");
            return false;
        }

        out_tokens.resize(static_cast<size_t>(needed));

        int n_tokens = llama_tokenize(
                g_vocab,
                text.c_str(),
                static_cast<int32_t>(text.size()),
                out_tokens.data(),
                static_cast<int32_t>(out_tokens.size()),
                kAddBos,
                kParseSpecial
        );

        if (n_tokens < 0) {
            LOGE("❌ Tokenization failed");
            return false;
        }

        out_tokens.resize(static_cast<size_t>(n_tokens));
        return true;
    }

    llama_batch build_batch(const llama_token *tokens,
                            int32_t count,
                            int32_t pos_start,
                            bool logits_last) {
        llama_batch batch = llama_batch_init(count, 0, 1);

        batch.n_tokens = count;

        for (int32_t i = 0; i < count; ++i) {
            batch.token[i]     = tokens[i];
            batch.pos[i]       = pos_start + i;
            batch.n_seq_id[i]  = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i]    = (logits_last && i == count - 1) ? 1 : 0;
        }

        return batch;
    }

    bool decode_tokens_locked(const std::vector<llama_token> &tokens, int32_t pos_start) {
        if (g_ctx == nullptr) {
            LOGE("❌ decode_tokens_locked: context is null");
            return false;
        }

        if (tokens.empty()) {
            return true;
        }

        for (int32_t offset = 0; offset < static_cast<int32_t>(tokens.size()); offset += kBatchSize) {
            const int32_t count = std::min<int32_t>(
                    kBatchSize,
                    static_cast<int32_t>(tokens.size()) - offset
            );

            const bool logits_last = (offset + count == static_cast<int32_t>(tokens.size()));

            llama_batch batch = build_batch(tokens.data() + offset, count, pos_start + offset, logits_last);
            const int rc = llama_decode(g_ctx, batch);
            llama_batch_free(batch);

            if (rc != 0) {
                LOGE("❌ llama_decode failed while decoding prompt chunk (rc=%d)", rc);
                return false;
            }
        }

        return true;
    }

    bool decode_one_token_locked(llama_token token, int32_t pos) {
        llama_batch batch = build_batch(&token, 1, pos, true);
        const int rc = llama_decode(g_ctx, batch);
        llama_batch_free(batch);

        if (rc != 0) {
            LOGE("❌ llama_decode failed while decoding generated token (rc=%d)", rc);
            return false;
        }

        return true;
    }

    void trim_kv_to_prefix_locked(int32_t keep_tokens) {
        if (g_ctx == nullptr) return;
        llama_memory_seq_rm(llama_get_memory(g_ctx), 0, keep_tokens, -1);
    }

    /*
     * After a failed decode the KV cache may hold a half-written suffix while
     * g_prompt_tokens still describes the previous prompt. The next call would
     * then "reuse" KV rows that no longer match. Drop everything instead.
     */
    void reset_kv_after_failure_locked() {
        if (g_ctx != nullptr) {
            llama_memory_clear(llama_get_memory(g_ctx), true);
        }
        g_prompt_tokens.clear();
        LOGE("♻️ KV cache cleared after decode failure");
    }

    std::string token_to_piece_string(const llama_vocab *vocab, llama_token token) {
        char stackBuf[256] = {0};

        int len = llama_token_to_piece(
                vocab,
                token,
                stackBuf,
                static_cast<int32_t>(sizeof(stackBuf)),
                0,
                false
        );

        if (len > 0) {
            return std::string(stackBuf, static_cast<size_t>(len));
        }

        if (len < 0) {
            std::vector<char> dynamicBuf(static_cast<size_t>(-len) + 1U, 0);
            len = llama_token_to_piece(
                    vocab,
                    token,
                    dynamicBuf.data(),
                    static_cast<int32_t>(dynamicBuf.size()),
                    0,
                    false
            );

            if (len > 0) {
                return std::string(dynamicBuf.data(), static_cast<size_t>(len));
            }
        }

        return "";
    }


    bool json_object_closed(const std::string &response) {
        int depth = 0;
        bool started = false;
        bool in_string = false;
        bool escape = false;
        for (char c : response) {
            if (in_string) {
                if (escape) { escape = false; }
                else if (c == '\\') { escape = true; }
                else if (c == '"') { in_string = false; }
                continue;
            }
            if (c == '"') { in_string = true; }
            else if (c == '{') { depth++; started = true; }
            else if (c == '}') { depth--; if (started && depth <= 0) return true; }
        }
        return false;
    }

    int32_t clamp_max_new_tokens(jint maxNewTokens) {
        if (maxNewTokens <= 0) return kDefaultNewTokens;
        return std::min<int32_t>(static_cast<int32_t>(maxNewTokens), kMaxNewTokensCap);
    }

    jstring make_empty_string(JNIEnv *env) {
        return env->NewStringUTF("");
    }

    jstring generate_impl(JNIEnv *env,
                          jstring prompt,
                          jint maxNewTokens,
                          jint mode,
                          jint maxSentences,
                          jobject callback) {
        if (prompt == nullptr) return make_empty_string(env);

        // GetStringUTFChars returns Modified UTF-8; read UTF-16 and encode ourselves.
        const jsize plen = env->GetStringLength(prompt);
        const jchar *pchars = env->GetStringChars(prompt, nullptr);
        if (pchars == nullptr) return make_empty_string(env);
        std::string prompt_text;
        prompt_text.reserve(static_cast<size_t>(plen));
        for (jsize i = 0; i < plen; ++i) {
            uint32_t cp = pchars[i];
            if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < plen) {
                const uint32_t lo = pchars[i + 1];
                if (lo >= 0xDC00 && lo <= 0xDFFF) {
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                    ++i;
                }
            }
            if (cp >= 0xD800 && cp <= 0xDFFF) cp = 0xFFFD;
            if (cp < 0x80) {
                prompt_text.push_back(static_cast<char>(cp));
            } else if (cp < 0x800) {
                prompt_text.push_back(static_cast<char>(0xC0 | (cp >> 6)));
                prompt_text.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            } else if (cp < 0x10000) {
                prompt_text.push_back(static_cast<char>(0xE0 | (cp >> 12)));
                prompt_text.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                prompt_text.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            } else {
                prompt_text.push_back(static_cast<char>(0xF0 | (cp >> 18)));
                prompt_text.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
                prompt_text.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
                prompt_text.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            }
        }
        env->ReleaseStringChars(prompt, pchars);

        if (prompt_text.empty()) return make_empty_string(env);

        const bool streaming = callback != nullptr;
        jmethodID onPartialMethod = nullptr;
        if (streaming) {
            jclass callbackClass = env->GetObjectClass(callback);
            if (callbackClass != nullptr) {
                onPartialMethod = env->GetMethodID(callbackClass, "onPartial", "(Ljava/lang/String;)V");
                env->DeleteLocalRef(callbackClass);
            }
            if (onPartialMethod == nullptr) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                LOGE("onPartial callback not found; streaming disabled");
            }
        }

        const bool json_mode = (mode == kModeJson);
        const bool chat_mode = (mode == kModeChat);
        const int sentence_budget = maxSentences <= 0 ? 2 : static_cast<int>(maxSentences);

        std::lock_guard<std::mutex> lock(g_state_mutex);

        if (g_model == nullptr || g_vocab == nullptr) {
            LOGE("Model not loaded");
            return make_empty_string(env);
        }
        if (!create_context_locked()) {
            LOGE("Context unavailable");
            return make_empty_string(env);
        }

        g_abort_requested.store(false);

        std::vector<llama_token> prompt_tokens;
        if (!tokenize_prompt_locked(prompt_text, prompt_tokens) || prompt_tokens.empty()) {
            return make_empty_string(env);
        }

        // Record the size first so a rejected prompt is visible in the stats, too.
        g_stat_prompt_tokens = static_cast<int32_t>(prompt_tokens.size());
        g_stat_reused_tokens = 0;
        g_stat_prompt_ms = 0;
        g_stat_gen_tokens = 0;
        g_stat_gen_ms = 0;

        if (static_cast<int32_t>(prompt_tokens.size()) >= (g_ctx_size - 8)) {
            LOGE("Prompt too long for context (%d >= %d)",
                 static_cast<int>(prompt_tokens.size()), g_ctx_size - 8);
            return make_empty_string(env);
        }

        size_t common_prefix = common_prefix_len(g_prompt_tokens, prompt_tokens);
        if (common_prefix == prompt_tokens.size() && common_prefix > 0) {
            common_prefix--;
        }
        if (!g_prompt_tokens.empty()) {
            trim_kv_to_prefix_locked(static_cast<int32_t>(common_prefix));
        }

        std::vector<llama_token> suffix_tokens(
                prompt_tokens.begin() + static_cast<long>(common_prefix),
                prompt_tokens.end());

        g_stat_prompt_tokens = static_cast<int32_t>(prompt_tokens.size());
        g_stat_reused_tokens = static_cast<int32_t>(common_prefix);
        g_stat_gen_tokens = 0;
        g_stat_gen_ms = 0;
        const int64_t t_prompt = now_ms();

        if (!decode_tokens_locked(suffix_tokens, static_cast<int32_t>(common_prefix))) {
            reset_kv_after_failure_locked();
            return make_empty_string(env);
        }
        g_prompt_tokens = prompt_tokens;
        g_stat_prompt_ms = now_ms() - t_prompt;
        const int64_t t_gen = now_ms();

        llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (sampler == nullptr) return make_empty_string(env);

        // Chat/free: mild repetition penalty stops loops; JSON: pure greedy.
        if (!json_mode) {
            llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
            llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, 1.10f, 0.0f, 0.0f));
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

        std::string response;
        std::string partialChunk;
        int32_t n_past = static_cast<int32_t>(prompt_tokens.size());
        const int32_t max_new = clamp_max_new_tokens(maxNewTokens);
        int sentences_done = 0;

        for (int i = 0; i < max_new; ++i) {
            if (g_abort_requested.load()) {
                LOGD("Generation aborted");
                break;
            }
            if (n_past >= g_ctx_size - 1) {
                LOGD("Context window full; stopping");
                break;
            }

            llama_token token = llama_sampler_sample(sampler, g_ctx, -1);
            if (llama_vocab_is_eog(g_vocab, token)) break;

            const std::string piece = token_to_piece_string(g_vocab, token);

            // A sentence is complete only when the next piece starts with
            // whitespace, so "3.14" and "Dr. Rao" are never cut.
            if (chat_mode
                && response.size() >= 12
                && piece_starts_with_space(piece)
                && ends_sentence(response)) {
                sentences_done++;
                if (sentences_done >= sentence_budget) break;
            }

            llama_sampler_accept(sampler, token);

            if (!piece.empty()) {
                response += piece;
                if (streaming) partialChunk += piece;
            }

            if (response.find("<|") != std::string::npos) break;

            if (!decode_one_token_locked(token, n_past)) break;
            n_past++;
            g_stat_gen_tokens++;

            if (streaming && onPartialMethod != nullptr && !json_mode && chunk_is_flushable(partialChunk)) {
                const size_t safe_len = utf8_complete_prefix_len(partialChunk);
                std::string ready = trim_copy(partialChunk.substr(0, safe_len));
                std::string carry = partialChunk.substr(safe_len);
                if (!looks_like_weak_chunk(ready)) {
                    send_partial_callback(env, callback, onPartialMethod, ready);
                }
                partialChunk = carry;
            }

            if (json_mode && json_object_closed(response)) break;
        }

        g_stat_gen_ms = now_ms() - t_gen;
        LOGD("timing: prompt %d tok (%d reused) %lld ms | gen %d tok %lld ms",
             g_stat_prompt_tokens, g_stat_reused_tokens, (long long) g_stat_prompt_ms,
             g_stat_gen_tokens, (long long) g_stat_gen_ms);

        response.resize(utf8_complete_prefix_len(response));
        partialChunk.resize(utf8_complete_prefix_len(partialChunk));

        if (streaming && onPartialMethod != nullptr && !json_mode) {
            std::string finalChunk = trim_copy(partialChunk);
            if (!finalChunk.empty() && !looks_like_weak_chunk(finalChunk)) {
                send_partial_callback(env, callback, onPartialMethod, finalChunk);
            }
        }

        llama_sampler_free(sampler);
        g_abort_requested.store(false);

        return make_jstring_utf8(env, response);
    }

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_revived_llm_LlamaBridge_nativeLoadModel(
        JNIEnv *env, jclass, jstring modelPath, jint contextSize, jint threads) {
    if (modelPath == nullptr) return JNI_FALSE;

    const char *path_chars = env->GetStringUTFChars(modelPath, nullptr);
    if (path_chars == nullptr) return JNI_FALSE;
    const std::string path(path_chars);
    env->ReleaseStringUTFChars(modelPath, path_chars);

    std::lock_guard<std::mutex> lock(g_state_mutex);
    ensure_backend_locked();

    const int32_t want_ctx = contextSize >= 256 ? static_cast<int32_t>(contextSize) : 1024;
    const int32_t want_threads = threads >= 1 ? static_cast<int32_t>(threads) : 4;

    if (g_model != nullptr && g_model_path == path
        && want_ctx == g_ctx_size && want_threads == g_threads) {
        return create_context_locked() ? JNI_TRUE : JNI_FALSE;
    }

    free_model_locked();
    g_ctx_size = want_ctx;
    g_threads = want_threads;

    LOGD("Loading model: %s (n_ctx=%d, threads=%d)", path.c_str(), g_ctx_size, g_threads);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(path.c_str(), mparams);
    if (g_model == nullptr) {
        LOGE("Model load failed");
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);
    g_model_path = path;

    if (g_vocab == nullptr || !create_context_locked()) {
        free_model_locked();
        return JNI_FALSE;
    }

    LOGD("Model ready");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvis_revived_llm_LlamaBridge_nativeGenerate(
        JNIEnv *env, jclass, jstring prompt, jint maxNewTokens, jint mode,
        jint maxSentences, jobject callback) {
    return generate_impl(env, prompt, maxNewTokens, mode, maxSentences, callback);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_revived_llm_LlamaBridge_nativeAbort(JNIEnv *, jclass) {
    g_abort_requested.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_revived_llm_LlamaBridge_nativeResetContext(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    g_abort_requested.store(false);
    if (g_model == nullptr) return;
    free_context_locked();
    create_context_locked();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_revived_llm_LlamaBridge_nativeFreeModel(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    g_abort_requested.store(false);
    free_model_locked();
    if (g_backend_initialized.load()) {
        llama_backend_free();
        g_backend_initialized.store(false);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_revived_llm_LlamaBridge_nativeSetThreads(JNIEnv *, jclass, jint threads) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    const int32_t n = threads < 1 ? 1 : (threads > 8 ? 8 : static_cast<int32_t>(threads));
    g_threads = n;
    if (g_ctx != nullptr) {
        llama_set_n_threads(g_ctx, n, n);
    }
    LOGD("threads set to %d", n);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvis_revived_llm_LlamaBridge_nativeLastStats(JNIEnv *env, jclass) {
    // No lock: plain counters, written only by the generating thread; a torn read is harmless.
    char buf[200];
    const double gen_s = g_stat_gen_ms / 1000.0;
    const double tps = gen_s > 0 ? g_stat_gen_tokens / gen_s : 0.0;
    snprintf(buf, sizeof(buf), "read %d tok (%d cached) in %.1f s, wrote %d tok in %.1f s (%.1f tok/s)",
             g_stat_prompt_tokens, g_stat_reused_tokens, g_stat_prompt_ms / 1000.0,
             g_stat_gen_tokens, gen_s, tps);
    return make_jstring_utf8(env, std::string(buf));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jarvis_revived_llm_LlamaBridge_nativeStatus(JNIEnv *env, jclass) {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    std::string status =
            std::string("model=") + (g_model ? "loaded" : "none") +
            ", context=" + (g_ctx ? "ready" : "none") +
            ", n_ctx=" + std::to_string(g_ctx_size) +
            ", threads=" + std::to_string(g_threads) +
            ", cached_prompt_tokens=" + std::to_string(g_prompt_tokens.size());
    return make_jstring_utf8(env, status);
}
