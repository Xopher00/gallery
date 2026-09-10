#include "LLMInference.h"
#include <jni.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_loadModel(JNIEnv* env, jobject thiz, jstring modelPath, jfloat minP,
                                            jfloat temperature, jfloat topP, jint topK, jfloat repeatPenalty,
                                            jboolean storeChats, jlong contextSize,
                                            jstring chatTemplate, jint nThreads, jboolean useMmap, jboolean useMlock) {
    jboolean    isCopy           = true;
    const char* modelPathCstr    = env->GetStringUTFChars(modelPath, &isCopy);
    auto*       llmInference     = new LLMInference();
    const char* chatTemplateCstr = env->GetStringUTFChars(chatTemplate, &isCopy);

    try {
        llmInference->loadModel(modelPathCstr, minP, temperature, topP, topK, repeatPenalty,
                                storeChats, contextSize, chatTemplateCstr, nThreads, useMmap, useMlock);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(modelPath, modelPathCstr);
        env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
        delete llmInference;
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return 0;
    }

    env->ReleaseStringUTFChars(modelPath, modelPathCstr);
    env->ReleaseStringUTFChars(chatTemplate, chatTemplateCstr);
    return reinterpret_cast<jlong>(llmInference);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_addChatMessage(JNIEnv* env, jobject thiz, jlong modelPtr, jstring message,
                                                 jstring role) {
    jboolean    isCopy       = true;
    const char* messageCstr  = env->GetStringUTFChars(message, &isCopy);
    const char* roleCstr     = env->GetStringUTFChars(role, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->addChatMessage(messageCstr, roleCstr);
    env->ReleaseStringUTFChars(message, messageCstr);
    env->ReleaseStringUTFChars(role, roleCstr);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_getResponseGenerationSpeed(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getResponseGenerationTime();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_getContextSizeUsed(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    return llmInference->getContextSizeUsed();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_close(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    delete llmInference;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_startCompletion(JNIEnv* env, jobject thiz, jlong modelPtr, jstring prompt) {
    jboolean    isCopy       = true;
    const char* promptCstr   = env->GetStringUTFChars(prompt, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        llmInference->startCompletion(promptCstr);
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(prompt, promptCstr);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return;
    }
    env->ReleaseStringUTFChars(prompt, promptCstr);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_completionLoop(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        std::string response = llmInference->completionLoop();
        return env->NewStringUTF(response.c_str());
    } catch (std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_stopCompletion(JNIEnv* env, jobject thiz, jlong modelPtr) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    llmInference->stopCompletion();
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_benchModel(JNIEnv* env, jobject /*unused*/, jlong modelPtr, jint pp, jint tg,
                                             jint pl) {
    auto* llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        LLMInference::BenchResult r = llmInference->benchModel(pp, tg, pl);
        // Flat array, not a Kotlin object: building one from JNI needs a mangled inner-class
        // lookup that R8 can strip, which then fails at runtime rather than at build time.
        jdouble      vals[4] = { r.prefill_seconds, r.decode_seconds, r.prefill_tokens_per_second,
                                 r.decode_tokens_per_second };
        jdoubleArray result  = env->NewDoubleArray(4);
        env->SetDoubleArrayRegion(result, 0, 4, vals);
        return result;
    } catch (std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_jegly_offlineLLM_smollm_SmolLM_getEmbedding(JNIEnv* env, jobject thiz, jlong modelPtr, jstring text) {
    jboolean    isCopy       = true;
    const char* textCstr     = env->GetStringUTFChars(text, &isCopy);
    auto*       llmInference = reinterpret_cast<LLMInference*>(modelPtr);
    try {
        std::vector<float> embedding = llmInference->getEmbedding(textCstr);
        env->ReleaseStringUTFChars(text, textCstr);
        jfloatArray result = env->NewFloatArray(jsize(embedding.size()));
        env->SetFloatArrayRegion(result, 0, jsize(embedding.size()), embedding.data());
        return result;
    } catch (std::exception& error) {
        env->ReleaseStringUTFChars(text, textCstr);
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}
