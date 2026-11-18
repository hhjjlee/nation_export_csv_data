// whisper.cpp ↔ Kotlin 연결 (com.kosub.player.asr.WhisperNative)
//
// transcribe()는 결과를 UTF-8 JSON 바이트로 돌려준다. 토큰 하나가 한글/한자 한 글자의 일부일 수 있어서
// (UTF-8이 중간에 잘림) JNI 문자열 대신 바이트 배열을 쓰고, Kotlin에서 UTF-8로 해석한다.
//
// {"lang":"en","lang_prob":0.97,"segments":[{"t0":0,"t1":250,"text":" Hello","logprob":-0.2,
//   "words":[[0,40," Hello"], ...]}]}   (시간 단위: 10ms)

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "KoSubWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

struct Handle {
    whisper_context *ctx = nullptr;
    std::atomic<bool> abort{false};
};

void log_callback(enum ggml_log_level level, const char *text, void * /*user*/) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
                                             : ANDROID_LOG_DEBUG;
    __android_log_write(prio, TAG, text);
}

bool abort_callback(void *user) {
    auto *h = static_cast<Handle *>(user);
    return h->abort.load();
}

void append_json_string(std::string &out, const std::string &s) {
    out.push_back('"');
    for (unsigned char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else {
                    out.push_back(static_cast<char>(c));
                }
        }
    }
    out.push_back('"');
}

// UTF-8 문자가 중간에서 끊기지 않았는지
bool utf8_complete(const std::string &s) {
    int need = 0;
    for (unsigned char c : s) {
        if (need > 0) {
            if ((c & 0xC0) != 0x80) return true;  // 잘못된 바이트: 더 기다려도 소용없음
            --need;
        } else if ((c & 0x80) == 0) {
            need = 0;
        } else if ((c & 0xE0) == 0xC0) {
            need = 1;
        } else if ((c & 0xF0) == 0xE0) {
            need = 2;
        } else if ((c & 0xF8) == 0xF0) {
            need = 3;
        }
    }
    return need == 0;
}

bool language_uses_spaces(const char *lang) {
    static const char *no_space[] = {"ja", "zh", "yue", "th", "lo", "my", "km", "bo"};
    for (const char *l : no_space) {
        if (std::strcmp(lang, l) == 0) return false;
    }
    return true;
}

jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr != nullptr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kosub_player_asr_WhisperNative_initContext(JNIEnv *env, jclass, jstring jpath) {
    whisper_log_set(log_callback, nullptr);
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (ctx == nullptr) {
        LOGW("모델을 불러오지 못했습니다");
        return 0;
    }
    auto *h = new Handle();
    h->ctx = ctx;
    LOGI("whisper 초기화 완료: %s", whisper_print_system_info());
    return reinterpret_cast<jlong>(h);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kosub_player_asr_WhisperNative_freeContext(JNIEnv *, jclass, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h == nullptr) return;
    if (h->ctx != nullptr) whisper_free(h->ctx);
    delete h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kosub_player_asr_WhisperNative_abort(JNIEnv *, jclass, jlong handle) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h != nullptr) h->abort.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kosub_player_asr_WhisperNative_systemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_kosub_player_asr_WhisperNative_transcribe(JNIEnv *env, jclass, jlong handle, jfloatArray jpcm,
                                                    jstring jlang, jint threads) {
    auto *h = reinterpret_cast<Handle *>(handle);
    if (h == nullptr || h->ctx == nullptr) return nullptr;
    whisper_context *ctx = h->ctx;
    h->abort.store(false);

    const jsize n = env->GetArrayLength(jpcm);
    std::vector<float> pcm(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jpcm, 0, n, pcm.data());

    const char *lang_c = env->GetStringUTFChars(jlang, nullptr);
    std::string lang(lang_c);
    env->ReleaseStringUTFChars(jlang, lang_c);

    const int n_threads = threads > 0 ? threads : 4;
    float lang_prob = 1.0f;

    // 언어 자동 감지: 확률까지 알 수 있도록 직접 감지한 뒤 그 언어로 인식한다
    if (lang.empty() || lang == "auto") {
        if (whisper_pcm_to_mel(ctx, pcm.data(), n, n_threads) != 0) return nullptr;
        std::vector<float> probs(static_cast<size_t>(whisper_lang_max_id() + 1), 0.0f);
        const int id = whisper_lang_auto_detect(ctx, 0, n_threads, probs.data());
        if (id >= 0) {
            lang = whisper_lang_str(id);
            lang_prob = probs[static_cast<size_t>(id)];
        } else {
            lang = "en";
            lang_prob = 0.0f;
        }
    }
    if (h->abort.load()) return to_bytes(env, "{\"aborted\":true}");

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = n_threads;
    params.translate = false;
    params.no_context = true;          // 앞 구간 문장을 프롬프트로 쓰지 않음 (반복 환각 방지)
    params.single_segment = false;
    params.print_special = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.token_timestamps = true;    // 단어 단위 시간 (자막 타이밍)
    params.language = lang.c_str();
    params.detect_language = false;
    params.suppress_blank = true;
    params.abort_callback = abort_callback;
    params.abort_callback_user_data = h;

    const int rc = whisper_full(ctx, params, pcm.data(), n);
    if (h->abort.load()) return to_bytes(env, "{\"aborted\":true}");
    if (rc != 0) {
        LOGW("whisper_full 실패: %d", rc);
        return nullptr;
    }

    const bool spaced = language_uses_spaces(lang.c_str());
    const whisper_token eot = whisper_token_eot(ctx);
    std::string out;
    out.reserve(4096);
    out += "{\"lang\":";
    append_json_string(out, lang);
    out += ",\"lang_prob\":";
    out += std::to_string(lang_prob);
    out += ",\"segments\":[";

    const int n_seg = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_seg; ++i) {
        if (i > 0) out.push_back(',');
        out += "{\"t0\":" + std::to_string(whisper_full_get_segment_t0(ctx, i));
        out += ",\"t1\":" + std::to_string(whisper_full_get_segment_t1(ctx, i));
        out += ",\"text\":";
        append_json_string(out, whisper_full_get_segment_text(ctx, i));

        // 토큰 → 단어 (공백으로 시작하는 토큰에서 새 단어, 글자가 완성될 때까지 이어 붙임)
        std::string words = "[";
        std::string cur;
        int64_t c0 = 0, c1 = 0;
        double sum_logp = 0.0;
        int n_text = 0;
        bool first_word = true;
        auto flush = [&]() {
            if (cur.empty()) return;
            if (!first_word) words.push_back(',');
            first_word = false;
            words += "[" + std::to_string(c0) + "," + std::to_string(c1) + ",";
            append_json_string(words, cur);
            words += "]";
            cur.clear();
        };
        const int n_tok = whisper_full_n_tokens(ctx, i);
        for (int j = 0; j < n_tok; ++j) {
            const whisper_token_data td = whisper_full_get_token_data(ctx, i, j);
            if (td.id >= eot) continue;  // 특수 토큰(시간, 언어 등)
            const char *piece_c = whisper_token_to_str(ctx, td.id);
            if (piece_c == nullptr) continue;
            const std::string piece(piece_c);
            sum_logp += td.plog;
            ++n_text;
            const bool starts_space = !piece.empty() && piece[0] == ' ';
            if (!cur.empty() && utf8_complete(cur) && (!spaced || starts_space)) flush();
            if (cur.empty()) c0 = td.t0;
            cur += piece;
            c1 = td.t1;
        }
        flush();
        words += "]";
        out += ",\"logprob\":" + std::to_string(n_text > 0 ? sum_logp / n_text : 0.0);
        out += ",\"words\":" + words + "}";
    }
    out += "]}";
    return to_bytes(env, out);
}
