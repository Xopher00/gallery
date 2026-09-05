#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <atomic>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex g_whisper_mutex;
static std::atomic<bool> g_cancelled{false};
// Guards the "abort_callback: observed cancellation" log so it fires at most once per
// transcription instead of once ever per process. Reset at the start of each transcription.
static std::atomic<bool> g_cancel_logged{false};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_google_ai_edge_gallery_whisper_WhisperEngine_loadModelNative(
        JNIEnv* env, jobject, jstring modelPath) {
    // Clear any stale cancellation flag left over from an earlier cancelled transcription so it
    // cannot leak into the abort_callback/encoder_begin_callback wiring of the next transcribe.
    g_cancelled.store(false);

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading whisper model: %s", path);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("Failed to load model");
        return 0L;
    }
    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_google_ai_edge_gallery_whisper_WhisperEngine_transcribeNative(
        JNIEnv* env, jobject, jlong handle, jfloatArray audioData, jstring language) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (!ctx) return env->NewStringUTF("");

    g_cancelled.store(false);
    g_cancel_logged.store(false);

    LOGI("transcribe: acquiring whisper mutex");
    std::lock_guard<std::mutex> lock(g_whisper_mutex);
    LOGI("transcribe: holding whisper mutex");

    jsize n_samples = env->GetArrayLength(audioData);
    jfloat* samples = env->GetFloatArrayElements(audioData, nullptr);

    const char* lang = env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress   = false;
    params.print_special    = false;
    params.print_realtime   = false;
    params.print_timestamps = false;
    params.translate        = false;
    params.language         = lang;
    params.n_threads        = 4;
    params.no_context       = true;
    params.single_segment   = false;
    params.abort_callback = [](void* d) -> bool {
        bool cancelled = static_cast<std::atomic<bool>*>(d)->load();
        if (cancelled) {
            if (!g_cancel_logged.exchange(true)) {
                LOGI("abort_callback: observed cancellation");
            }
        }
        return cancelled;
    };
    params.abort_callback_user_data = &g_cancelled;
    params.encoder_begin_callback = [](struct whisper_context*, struct whisper_state*, void* d) -> bool {
        return !static_cast<std::atomic<bool>*>(d)->load();
    };
    params.encoder_begin_callback_user_data = &g_cancelled;

    int result = whisper_full(ctx, params, samples, n_samples);

    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    // Consume and clear the cancellation flag now that whisper_full() has returned and the
    // outcome has been observed, so it cannot leak into the next transcribeNative() call.
    // Still holds g_whisper_mutex here.
    bool wasCancelled = g_cancelled.exchange(false);

    if (wasCancelled) {
        LOGI("transcription cancelled");
        LOGI("transcribe: released whisper mutex");
        return env->NewStringUTF("");
    }

    if (result != 0) {
        LOGE("whisper_full failed: %d", result);
        LOGI("transcribe: released whisper mutex");
        return env->NewStringUTF("");
    }

    std::string text;
    int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; i++) {
        const char* segment = whisper_full_get_segment_text(ctx, i);
        if (segment) text += segment;
    }

    // Trim leading/trailing whitespace
    size_t start = text.find_first_not_of(" \t\n\r");
    if (start == std::string::npos) {
        LOGI("transcribe: released whisper mutex");
        return env->NewStringUTF("");
    }
    size_t end = text.find_last_not_of(" \t\n\r");
    text = text.substr(start, end - start + 1);

    LOGI("transcribe: released whisper mutex");
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_google_ai_edge_gallery_whisper_WhisperEngine_freeModelNative(
        JNIEnv*, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (ctx) {
        LOGI("free: waiting for in-flight transcribe...");
        std::lock_guard<std::mutex> lock(g_whisper_mutex);
        LOGI("free: holding whisper mutex");
        whisper_free(ctx);
        LOGI("free: freed");
    }
}

JNIEXPORT void JNICALL
Java_com_google_ai_edge_gallery_whisper_WhisperEngine_cancelTranscriptionNative(
        JNIEnv*, jobject) {
    g_cancelled.store(true);
    LOGI("cancelTranscriptionNative: cancellation requested");
}

} // extern "C"
