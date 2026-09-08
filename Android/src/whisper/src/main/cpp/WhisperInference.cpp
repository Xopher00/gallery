#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_google_ai_edge_gallery_whisper_WhisperEngine_loadModelNative(
        JNIEnv* env, jobject, jstring modelPath) {
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

    int result = whisper_full(ctx, params, samples, n_samples);

    env->ReleaseFloatArrayElements(audioData, samples, JNI_ABORT);
    env->ReleaseStringUTFChars(language, lang);

    if (result != 0) {
        LOGE("whisper_full failed: %d", result);
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
    if (start == std::string::npos) return env->NewStringUTF("");
    size_t end = text.find_last_not_of(" \t\n\r");
    text = text.substr(start, end - start + 1);

    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_google_ai_edge_gallery_whisper_WhisperEngine_freeModelNative(
        JNIEnv*, jobject, jlong handle) {
    auto* ctx = reinterpret_cast<whisper_context*>(handle);
    if (ctx) {
        whisper_free(ctx);
        LOGI("Model freed");
    }
}

} // extern "C"
