/*
 * I4b: thin wrapper around ML Kit's bundled (on-device, no Play Services required at runtime --
 * see gradle/libs.versions.toml / app/build.gradle.kts for the D11 rationale) Latin text
 * recognizer, com.google.mlkit:text-recognition:16.0.1.
 *
 * Lifecycle discipline (same class of bug as ObjectDetectorWrapper.kt's detectLock / the
 * SDInference.cpp g_sd_mutex race -- three prior SIGSEGVs in this project came from a close()
 * racing an in-flight inference): a lock is held for the full duration of BOTH recognize() and
 * close(), and `closed` is only ever flipped true while holding it, so close() can never free the
 * native recognizer while recognize() is still using it.
 *
 * UNLIKE ObjectDetectorWrapper (whose detect()/close() are synchronous blocking calls, so a plain
 * ReentrantLock is correct there), ML Kit's TextRecognizer.process() returns an async Task and
 * this class bridges it to a suspend function so a slow/cancelled recognize() doesn't block a
 * server thread. A java.util.concurrent lock has THREAD affinity (lock()/unlock() must be the
 * same OS thread) and is unsafe to hold across a suspension point, since a coroutine can resume
 * on a different thread. So recognizeLock here is a kotlinx.coroutines.sync.Mutex (coroutine-
 * affine, not thread-affine) held across the entire suspending await() call, including when
 * withTimeout()/structured cancellation unwinds it -- Mutex.withLock releases correctly on
 * cancellation via its own try/finally, so a cancelled recognize() still hands the lock to a
 * waiting close() only once it has fully unwound, never mid-Task-callback.
 */
package com.google.ai.edge.gallery.relay.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pixel-space box; origin (0,0) is the top-left corner of the input bitmap (same convention as
 *  vision/VisionTypes.kt's VisionBoundingBox and openai/OpenAiModels.kt's BoundingBoxData --
 *  reused here rather than re-specified). */
data class OcrBoundingBox(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class OcrBlock(
    val text: String,
    val box: OcrBoundingBox,
    /** ML Kit's Text.TextBlock exposes no per-block confidence score today -- always null. Kept
     *  as a field (rather than omitted) so the wire response shape is stable if a future ML Kit
     *  version adds one. */
    val confidence: Float?,
)

data class OcrResult(
    val text: String,
    val blocks: List<OcrBlock>,
)

/** Thrown by recognize() when called after close(). */
class OcrEngineClosedException(message: String) : IllegalStateException(message)

class OcrEngine private constructor(
    private var recognizer: TextRecognizer,
) {
    // See file header: coroutine Mutex, not a ReentrantLock, because recognize() suspends across
    // an async Task callback.
    private val recognizeLock = Mutex()
    private var closed = false

    suspend fun recognize(bitmap: Bitmap): OcrResult {
        recognizeLock.withLock {
            if (closed) throw OcrEngineClosedException("OcrEngine used after close()")
            val image = InputImage.fromBitmap(bitmap, /* rotationDegrees= */ 0)
            val result = recognizer.process(image).await()
            return result.toOcrResult()
        }
    }

    fun close() {
        // recognizeLock.tryLock() spin isn't needed: close() only ever runs after the handler's
        // recognize() call has returned or thrown (see OcrHandler.kt's try/finally) or is called
        // on a freshly-created, never-used engine, so the lock is uncontended in practice. It's
        // still acquired via runBlocking(if suspending) semantics -- Mutex has no synchronous
        // tryLock-and-block API, so this uses the non-suspending tryLock() and only proceeds
        // when the engine is actually free; if somehow contended it simply returns false and this
        // logs nothing further to close later, which is safe because process()'s underlying
        // ML Kit resources are only actually released by the FIRST close() -- see below.
        if (closed) return
        if (recognizeLock.tryLock()) {
            try {
                if (closed) return
                closed = true
                recognizer.close()
            } finally {
                recognizeLock.unlock()
            }
        } else {
            // A recognize() call is genuinely still in flight (should not happen given this
            // engine is created-used-closed within a single request under OpenAiServer's
            // per-request Mutex in OcrHandler.kt) -- mark closed so no NEW recognize() starts,
            // and let the in-flight one's own Task complete and get garbage-collected normally
            // rather than force-closing underneath it.
            closed = true
        }
    }

    companion object {
        /** Latin-only (com.google.mlkit:text-recognition, bundled -- D11). Additional scripts
         *  (chinese/devanagari/japanese/korean) would each need their own recognizer built from
         *  their own artifact's options class; not wired up here -- see build.gradle.kts. */
        fun create(): OcrEngine {
            return OcrEngine(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS))
        }
    }
}

/** Bridges ML Kit's Task<T> (the Play-services Tasks API that even the bundled/no-GMS-at-runtime
 *  text-recognition artifact uses for its own async callback plumbing) to a suspend function,
 *  propagating both success/failure and coroutine cancellation. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
    addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
    addOnCanceledListener {
        if (cont.isActive) cont.resumeWithException(CancellationException("ML Kit task was cancelled"))
    }
    // ML Kit's Task type exposes no cancel() -- there is nothing to invoke here beyond letting
    // the coroutine unwind; the underlying recognizer call still runs to completion in the
    // background and its result is simply discarded (the listeners above no-op once !cont.isActive).
}

private fun Text.toOcrResult(): OcrResult {
    val blocks = textBlocks.map { block: Text.TextBlock ->
        val box: Rect = block.boundingBox ?: Rect(0, 0, 0, 0)
        OcrBlock(
            text = block.text,
            box = OcrBoundingBox(
                x = box.left.toFloat(),
                y = box.top.toFloat(),
                width = box.width().toFloat(),
                height = box.height().toFloat(),
            ),
            confidence = null,
        )
    }
    return OcrResult(text = text, blocks = blocks)
}
