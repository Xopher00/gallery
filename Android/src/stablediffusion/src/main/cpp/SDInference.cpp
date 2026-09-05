#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include "stable-diffusion.h"

#define LOG_TAG "SDInference"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static std::atomic<int> g_progress_step(0);
static std::atomic<int> g_progress_total(0);
static std::atomic<bool> g_cancelled(false);
// Guards the "observed g_cancelled=true" log in progress_callback so it fires at most once per
// generation instead of on every single progress tick. Reset at the start of each generation.
static std::atomic<bool> g_cancel_logged(false);

// Guards the whole body of generateImageNative and freeContextNative so free_sd_ctx() can never
// run concurrently with an in-flight generate_image() call on the OpenMP threads (that race was
// the cause of the SEGV_MAPERR crash in ggml_vec_dot_f32 / ggml_compute_forward_mul_mat seen in
// DropBox 2026-09-02 20:51:25).
static std::mutex g_sd_mutex;

static void progress_callback(int step, int steps, float /*time*/, void* /*data*/) {
    g_progress_step.store(step);
    g_progress_total.store(steps);
    if (g_cancelled.load()) {
        if (!g_cancel_logged.exchange(true)) {
            LOGI("progress_callback observed g_cancelled=true (step=%d/%d)", step, steps);
        }
    }
}

// Polled by ggml between graph nodes (never mid-op) via sd_set_abort_callback(). Returning true
// stops computation as soon as the current node finishes.
static bool sd_abort_cb(void* /*data*/) {
    return g_cancelled.load();
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_google_ai_edge_gallery_stablediffusion_StableDiffusion_loadModelNative(
        JNIEnv* env, jobject /*thiz*/, jstring modelPath, jint nThreads) {
    // Loading is not itself cancellable, but a stale g_cancelled=true left over from an earlier
    // cancelled generation must not leak into the abort callback that gets wired up below via
    // sd_set_abort_callback() once the context exists.
    g_cancelled.store(false);

    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading SD model: %s (threads=%d)", path, nThreads);

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = path;
    params.n_threads = (int)nThreads;
    params.vae_decode_only = true;
    params.enable_mmap = true;

    sd_set_log_callback([](sd_log_level_t level, const char* text, void*) {
        if (level == SD_LOG_ERROR) {
            LOGE("[SD] %s", text);
        } else if (level == SD_LOG_INFO) {
            LOGI("[SD] %s", text);
        }
    }, nullptr);

    sd_set_progress_callback(progress_callback, nullptr);

    sd_ctx_t* ctx = new_sd_ctx(&params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!ctx) {
        LOGE("Failed to create SD context");
        return 0L;
    }

    sd_set_abort_callback(ctx, sd_abort_cb, nullptr);
    LOGI("SD model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jbyteArray JNICALL
Java_com_google_ai_edge_gallery_stablediffusion_StableDiffusion_generateImageNative(
        JNIEnv* env, jobject /*thiz*/, jlong ctxHandle,
        jstring prompt, jstring negPrompt,
        jint width, jint height, jint steps, jfloat cfgScale, jlong seed) {

    sd_ctx_t* ctx = reinterpret_cast<sd_ctx_t*>(ctxHandle);
    if (!ctx) {
        LOGE("generateImage called with null context");
        return nullptr;
    }

    LOGI("generate: acquiring sd mutex");
    std::lock_guard<std::mutex> lock(g_sd_mutex);
    LOGI("generate: holding sd mutex");

    const char* promptStr = env->GetStringUTFChars(prompt, nullptr);
    const char* negStr = env->GetStringUTFChars(negPrompt, nullptr);

    g_progress_step.store(0);
    g_progress_total.store(steps);
    g_cancelled.store(false);
    g_cancel_logged.store(false);

    sd_img_gen_params_t genParams;
    sd_img_gen_params_init(&genParams);
    genParams.prompt = promptStr;
    genParams.negative_prompt = negStr;
    genParams.width = (int)width;
    genParams.height = (int)height;
    genParams.seed = (int64_t)seed;
    genParams.batch_count = 1;
    genParams.sample_params.sample_steps = (int)steps;
    genParams.sample_params.guidance.txt_cfg = (float)cfgScale;
    // EULER_A_SAMPLE_METHOD = 1 (good default for SD1.5)
    genParams.sample_params.sample_method = EULER_A_SAMPLE_METHOD;

    LOGI("Generating image: %dx%d, steps=%d, cfg=%.1f, seed=%lld",
         width, height, steps, cfgScale, (long long)seed);

    sd_image_t* result = generate_image(ctx, &genParams);

    env->ReleaseStringUTFChars(prompt, promptStr);
    env->ReleaseStringUTFChars(negPrompt, negStr);

    // Consume and clear the cancellation flag now that generate_image() has returned and the
    // outcome has been observed, so it cannot leak into the next generateImageNative() call or
    // into the abort callback ggml polls on a subsequent operation. Still holds g_sd_mutex here.
    bool wasCancelled = g_cancelled.exchange(false);

    if (wasCancelled) {
        LOGI("generation cancelled");
        if (result) {
            if (result->data) free(result->data);
            free(result);
        }
        LOGI("generate: released sd mutex");
        return nullptr;
    }

    if (!result) {
        LOGE("generate_image returned null");
        LOGI("generate: released sd mutex");
        return nullptr;
    }

    if (!result->data) {
        LOGE("generate_image returned image with null data");
        free(result);
        LOGI("generate: released sd mutex");
        return nullptr;
    }

    // result is channel=3 (RGB), width*height*3 bytes
    int dataSize = (int)(result->width * result->height * result->channel);
    LOGI("Image generated: %ux%u ch=%u (%d bytes)",
         result->width, result->height, result->channel, dataSize);

    jbyteArray byteArr = env->NewByteArray(dataSize);
    env->SetByteArrayRegion(byteArr, 0, dataSize,
                            reinterpret_cast<const jbyte*>(result->data));

    // result->data was allocated with malloc() (util.cpp tensor_to_sd_image) and result itself
    // with calloc() (stable-diffusion.cpp generate_image); both must be released with free(),
    // not delete[] (delete[]/malloc-calloc mismatch is undefined behaviour).
    free(result->data);
    free(result);

    LOGI("generate: released sd mutex");
    return byteArr;
}

JNIEXPORT jint JNICALL
Java_com_google_ai_edge_gallery_stablediffusion_StableDiffusion_getProgressStep(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return (jint)g_progress_step.load();
}

JNIEXPORT jint JNICALL
Java_com_google_ai_edge_gallery_stablediffusion_StableDiffusion_getProgressTotal(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return (jint)g_progress_total.load();
}

JNIEXPORT void JNICALL
Java_com_google_ai_edge_gallery_stablediffusion_StableDiffusion_cancelGenerationNative(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    g_cancelled.store(true);
    LOGI("cancel: g_cancelled set true");
}

JNIEXPORT void JNICALL
Java_com_google_ai_edge_gallery_stablediffusion_StableDiffusion_freeContextNative(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong ctxHandle) {
    if (ctxHandle == 0L) return;
    sd_ctx_t* ctx = reinterpret_cast<sd_ctx_t*>(ctxHandle);

    LOGI("free: waiting for in-flight generate...");
    std::lock_guard<std::mutex> lock(g_sd_mutex);
    LOGI("free: acquired sd mutex");

    free_sd_ctx(ctx);
    LOGI("SD context freed");
    LOGI("free: released sd mutex");
}

} // extern "C"
