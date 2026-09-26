# relay/vision

This directory has three vision tools. Each tool serves one route: `POST /v1/vision/detect`,
`/segment`, and `/ocr`. `ServerRoutes.kt` defines the routes. `OpenAiModels.kt` defines the request
and response types. `handlers/VisionHandler.kt` and `handlers/OcrHandler.kt` map the internal
result types to the wire format.

None of these tools run a general vision-language model. Each tool wraps one fixed-purpose Google
library.

Detection and segmentation use MediaPipe `tasks-vision` only, with a GPU delegate and a CPU
fallback. There is no raw `.tflite` or QNN path. OCR uses ML Kit's bundled text recognizer, so the
server needs no Play Services at runtime.

## What each file does

- `ObjectDetectorWrapper.kt` wraps the MediaPipe Object Detector. It returns boxes and labels from
  the COCO 80-class set. It reads the labels from the model's own embedded metadata, not from a
  hardcoded list.
- `ImageSegmenterWrapper.kt` wraps the MediaPipe Image Segmenter. It labels each pixel with one of
  21 PASCAL VOC classes. `ModelCatalog.kt` hardcodes this label list, because the MediaPipe Java
  API has no reliable way to read labels back from the model at runtime.
- `OcrEngine.kt` wraps ML Kit's bundled Latin text recognizer. It reads text out of an image.
- `ModelCatalog.kt` lists the fixed set of `.task` and `.tflite` files this server can load, and
  the path where it expects to find each file.
- `VisionTypes.kt` defines plain internal result types. These types stay separate from the
  `@Serializable` wire types in `OpenAiModels.kt` on purpose. `VisionHandler.kt` is the only file
  that maps between the two.
- `VisionToolListing.kt` lists the vision tools in `GET /v1/models`, so a client's model picker
  shows them. OCR is always listed. Detection and segmentation are listed only when their model
  file is present.

## Detection and segmentation need a manual step first

Object detection and segmentation each need a model file that MediaPipe does not ship with the
app: `efficientdet_lite0.tflite` and `deeplab_v3.tflite`. This server has no download flow for
these files. This is a deliberate scope limit.

`ModelCatalog.kt` checks whether the file exists at the expected path. If the file is missing, the
server returns a 503 response that names the missing file and the Google-hosted URL to get it
from. The expected path is:

```
{context.getExternalFilesDir(null)}/vision_models/<file>.tflite
```

This path uses app-external storage. A person can push a file there directly with `adb push`,
without access to app-private storage. Detection and segmentation return a 503 response until a
person downloads the two files by hand and pushes them to that path.

OCR has no such gap. ML Kit bundles its recognizer inside the library dependency. OCR needs no
external model file, and it works as soon as the server starts.

## Why each wrapper holds a lock during close

Each wrapper in this directory holds a lock for the full duration of its inference call and its
`close()` call. Each wrapper sets `closed = true` only while it holds that lock.

This lock protects against a specific bug. This project has already hit this bug three times: in
`SDInference.cpp`'s `g_sd_mutex`/`free_sd_ctx` race, and in `WhisperInference.cpp`. In each case, a
`close()` call that ran during an in-flight inference call freed a native object that the
inference call still needed. This crashed the process.

`ObjectDetectorWrapper` and `ImageSegmenterWrapper` use a plain `ReentrantLock`, because their
MediaPipe calls run synchronously. `OcrEngine` uses a coroutine `Mutex` instead. ML Kit's
`recognize()` call suspends during an async `Task` callback, and a thread-affine lock is not safe
to hold across a suspension point.

## Before you change this code

Check this README against the current code first. Run `git log` on this directory to see what has
changed since the README was last updated.
