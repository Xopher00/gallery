/*
 * Ported from mobile-server (com.server.edge.gallery) into this project (relay).
 */
package com.google.ai.edge.gallery.relay.openai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ChatCompletionRequest(
    val model: String,
    // Box (F4): content is JsonElement, not String, because OpenAI vision requests send either
    // a plain string OR an array of content parts ({"type":"text",...} / {"type":"image_url",...}).
    // Parsing/validation lives in OpenAiServer.parseMessageContent -- see that function for the
    // exact accepted shapes and error cases (non-data URLs, unsupported-model, caps).
    val messages: List<ChatMessageIn>,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val max_tokens: Int? = null,
    val stream: Boolean = false,
    val tools: List<OpenAiTool>? = null,
    val tool_choice: JsonElement? = null,
    // Box: optional per-request accelerator override ("cpu" | "gpu" | "npu" | "tpu", case
    // insensitive, matched against data.Accelerator). Validated in OpenAiServer; unknown
    // value -> HTTP 400. Omitted -> keep whatever the model is currently loaded with.
    val accelerator: String? = null,
)

// Box (F4): request-side message DTO. `content` is left as a raw JsonElement here (instead of a
// typed sealed class) so both accepted shapes -- plain string, and an array of
// {"type":"text"|"image_url", ...} parts -- deserialize without a custom JsonContentPolymorphicSerializer.
// OpenAiServer.parseMessageContent() is the single place that interprets it.
@Serializable
data class ChatMessageIn(
    val role: String,   // "system", "user", "assistant", "tool"
    val content: JsonElement? = null,
    // Box (F3): present on "tool" role messages replying to a prior tool_calls entry.
    val tool_call_id: String? = null,
    // Box (F3): present on "assistant" messages that themselves contain tool calls (history replay).
    val tool_calls: List<OpenAiToolCall>? = null,
)

// Box: response-side message DTO (assistant output). Content here is always plain text -- this
// server never emits multimodal content back to the client.
@Serializable
data class ChatMessage(
    val role: String,   // "system", "user", "assistant"
    val content: String,
    val tool_calls: List<OpenAiToolCall>? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: Usage? = null
)

@Serializable
data class ChatChoice(
    val index: Int,
    val message: ChatMessage,
    val finish_reason: String? = null
)

// --- Tool calls (F3) ---
// OpenAI shape: choices[].message.tool_calls[] with function.arguments as a JSON STRING (not a
// nested object) -- matches the wire format OpenAI clients (incl. Anthropic-shim translation)
// expect. See OpenAiServer for why this is currently unreachable (501) on this runtime.

@Serializable
data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiToolCallFunction,
)

@Serializable
data class OpenAiToolCallFunction(
    val name: String,
    val arguments: String,
)

@Serializable
data class ChatToolCallDelta(
    val index: Int,
    val id: String? = null,
    val type: String? = null,
    val function: OpenAiToolCallFunctionDelta? = null,
)

@Serializable
data class OpenAiToolCallFunctionDelta(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChatChunkChoice>
)

@Serializable
data class ChatChunkChoice(
    val index: Int,
    val delta: ChatDelta,
    val finish_reason: String? = null
)

@Serializable
data class ChatDelta(
    val role: String? = null,
    val content: String? = null,
    val tool_calls: List<ChatToolCallDelta>? = null,
)

@Serializable
data class ModelsListResponse(
    val `object`: String = "list",
    val data: List<ModelData>
)

@Serializable
data class ModelData(
    val id: String,
    val `object`: String = "model",
    val created: Long = 0,
    val owned_by: String = "local",
    // Box: extra fields so a client can tell what it is getting.
    // Runtime the model actually loaded under (e.g. "litert_lm", "aicore"), lowercased
    // from the model's RuntimeType enum.
    val runtime: String? = null,
    // The accelerator actually serving this model right now (e.g. "gpu"), lowercased. While the
    // model is loaded this is the engine's real accelerator, which can differ from
    // `preferred_accelerator` below (e.g. a per-request accelerator override on
    // chat/completions reinitialized the engine without durably changing the stored
    // preference). While the model is NOT loaded there is no engine to report on, so this
    // equals `preferred_accelerator`. Empty string if this cannot be determined.
    val accelerator: String = "",
    // The stored accelerator PREFERENCE (from ConfigKeys.ACCELERATOR) -- what the model would
    // load onto next, regardless of whether it is currently loaded or what it is actually
    // running on. Always populated, loaded or not.
    val preferred_accelerator: String = "",
    // Every accelerator this model can run on (e.g. ["cpu", "gpu", "npu"]), lowercased, from
    // Model.accelerators / ConfigKeys.COMPATIBLE_ACCELERATORS -- this is what the model
    // SUPPORTS, which can be a larger set than the single value in `accelerator` above.
    val compatible_accelerators: List<String> = emptyList(),
    // Retained for compatibility with existing clients: duplicates `accelerator` above as a
    // single-element list. Before this field carried the wrong information (it read the same
    // single current-accelerator value but was named as if it were the plural compatible set),
    // so a client that ticked CPU/GPU/NPU at import time and inspected this field would see only
    // one of the three and could reasonably conclude the other two were lost -- use `accelerator`
    // and `compatible_accelerators` above instead.
    val accelerators: List<String> = emptyList(),
    // WP: "loaded" iff the model currently has a live runtime instance (model.instance != null),
    // "available" if it's downloaded but not initialized. GET /v1/models and GET
    // /v1/models/{id} now list every downloaded model (not just loaded ones) -- this field is
    // how a client tells the two apart without calling /v1/models/{id}/load speculatively.
    val status: String = "available"
)

// WP: request body for POST /v1/models/{id}/load. `accelerator` is optional ("cpu"|"gpu"|
// "npu"|"tpu", case-insensitive, same values as ChatCompletionRequest.accelerator) -- omitted
// keeps the model's currently configured accelerator (or the default if not yet initialized).
@Serializable
data class LoadModelRequest(
    val accelerator: String? = null,
)

// --- Tool definitions ---

@Serializable
data class OpenAiTool(
    val type: String = "function",
    val function: OpenAiToolFunction
)

@Serializable
data class OpenAiToolFunction(
    val name: String,
    val description: String? = null,
    val parameters: OpenAiToolParameters? = null
)

@Serializable
data class OpenAiToolParameters(
    val type: String = "object",
    val properties: Map<String, OpenAiToolProperty>? = null,
    val required: List<String>? = null
)

@Serializable
data class OpenAiToolProperty(
    val type: String,
    val description: String? = null
)

// --- Legacy completions API ---

@Serializable
data class CompletionRequest(
    val model: String,
    val prompt: String,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val max_tokens: Int? = null,
    val stream: Boolean = false,
    // Box: see ChatCompletionRequest.accelerator.
    val accelerator: String? = null,
)

@Serializable
data class CompletionResponse(
    val id: String,
    val `object`: String = "text_completion",
    val created: Long,
    val model: String,
    val choices: List<CompletionChoice>,
    val usage: Usage? = null
)

@Serializable
data class CompletionChoice(
    val index: Int,
    val text: String,
    val finish_reason: String? = null
)

@Serializable
data class CompletionChunk(
    val id: String,
    val `object`: String = "text_completion",
    val created: Long,
    val model: String,
    val choices: List<CompletionChunkChoice>
)

@Serializable
data class CompletionChunkChoice(
    val index: Int,
    val text: String,
    val finish_reason: String? = null
)

// --- Error envelope for auth failures ---

@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(val message: String, val type: String = "invalid_request_error")

// --- Audio transcriptions API (POST /v1/audio/transcriptions) ---
// Request is multipart/form-data (file, model, language, prompt, response_format, temperature),
// so unlike the other endpoints there's no @Serializable request DTO here -- see
// openai/handlers/AudioTranscriptionHandler.kt for the field parsing.

@Serializable
data class TranscriptionResponse(val text: String)

// --- Image generations API (POST /v1/images/generations) ---

@Serializable
data class ImageGenerationRequest(
    val prompt: String,
    val model: String? = null,
    val n: Int? = null,
    val size: String? = null,
    val response_format: String? = null,
)

@Serializable
data class ImageGenerationResponse(
    val created: Long,
    val data: List<ImageData>,
)

@Serializable
data class ImageData(
    val b64_json: String? = null,
    val url: String? = null,
)

// --- Vision API: object detection + segmentation (I4a, MediaPipe tasks-vision only) ---
// Shared image input shape for both routes below: same "data:image/...;base64,..." convention
// used by the chat-completions image_url content part (see handlers/MultimodalContent.kt),
// deliberately reused rather than re-specified so this server has exactly one accepted image
// encoding everywhere.

@Serializable
data class VisionDetectRequest(
    val image: String,
    val model: String? = null,
    val max_results: Int? = null,
    val score_threshold: Float? = null,
)

@Serializable
data class VisionDetectResponse(val detections: List<DetectionData>)

@Serializable
data class DetectionData(
    val label: String,
    val score: Float,
    val box: BoundingBoxData,
)

// Pixel-space box in the original (undecoded-scale) bitmap; origin (0,0) is the top-left corner,
// x increases rightward, y increases downward -- standard Android Bitmap/canvas convention.
@Serializable
data class BoundingBoxData(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

@Serializable
data class VisionSegmentRequest(
    val image: String,
    val model: String? = null,
)

@Serializable
data class VisionSegmentResponse(
    // Grayscale PNG, base64-encoded, same width/height as the input image; each pixel's 8-bit
    // gray value is the category index -- look it up in `categories` below to get its label.
    val category_mask_png_b64: String,
    val categories: List<SegmentCategoryData>,
)

@Serializable
data class SegmentCategoryData(val value: Int, val label: String)

// --- OCR API: POST /v1/vision/ocr (I4b, bundled ML Kit text recognition -- D11, no Play Services
// required at runtime). Same "data:image/...;base64,..." image convention as the vision detect/
// segment requests above, reused rather than re-specified.

@Serializable
data class OcrRequest(
    val image: String,
    // Accepted and ignored -- there is only one (Latin) recognizer wired up, see vision/OcrEngine.kt.
    // Kept as a field for client convenience/forward-compat rather than 400ing on an unknown key.
    val model: String? = null,
)

@Serializable
data class OcrResponse(
    val text: String,
    val blocks: List<OcrBlockData>,
)

@Serializable
data class OcrBlockData(
    val text: String,
    val box: OcrBoundingBoxData,
    val confidence: Float? = null,
)

// Pixel-space box in the original (undecoded-scale) bitmap; origin (0,0) is the top-left corner,
// x increases rightward, y increases downward -- same convention as BoundingBoxData above
// (standard Android Bitmap/canvas convention), so the two routes agree.
@Serializable
data class OcrBoundingBoxData(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

// --- Agent tools API (WP-H): POST /v1/agent/run, GET /v1/agent/tools ---
// Runs the app's OWN compiled-in agent tools (MobileActionsTools.kt -- flashlight, wifi/
// bluetooth/sound settings, dial, SMS-compose, calendar, contacts, etc.) headlessly. This is
// NOT client-supplied tool calling: the client cannot define tools, only run/inspect the fixed
// built-in set. See handlers/AgentHandler.kt for the allowlist/safety logic.

@Serializable
data class AgentRunRequest(
    val prompt: String,
    // Which initialized model to run the agent loop on. Omitted -> the first initialized model
    // with llmSupportMobileActions=true is used; 404 if none is loaded.
    val model: String? = null,
    // Caps how many of the model's tool calls are actually allowed to execute during this run
    // (further calls are answered with a "blocked: max_steps reached" tool result instead of
    // being run). Default and hard cap live in AgentHandler.kt.
    val max_steps: Int? = null,
)

@Serializable
data class AgentRunResponse(
    val answer: String,
    val steps: List<AgentStepData>,
    val model: String,
)

@Serializable
data class AgentStepData(
    val tool: String,
    val arguments: Map<String, String>,
    val result: String,
)

@Serializable
data class AgentToolsResponse(val tools: List<AgentToolData>)

@Serializable
data class AgentToolData(
    val name: String,
    val description: String,
    // OpenAI-function-call-shaped JSON schema ({"type":"object","properties":{...},"required":
    // [...]}), reflected directly off the @Tool/@ToolParam annotations by litertlm's
    // ReflectionTool -- not hand-maintained here.
    val parameters: JsonElement,
    // Whether the caller's API key is currently allowed to let this tool actually execute its
    // device-side effect (see AgentHandler.DEFAULT_ALLOWED_TOOLS).
    val allowlisted: Boolean,
)

// --- Anthropic-style /v1/messages shim (F3, EXPERIMENTAL) ---
// Thin translator only: request/response are re-shaped and handed to the same
// handleChatCompletion() logic OpenAI clients hit. Non-streaming only for now (see
// OpenAiServer.handleAnthropicMessages) -- SSE with Anthropic's event names is not implemented.
// `system` is Anthropic's TOP-LEVEL field (not a message in `messages`), handled explicitly.

@Serializable
data class AnthropicMessagesRequest(
    val model: String,
    val max_tokens: Int,
    val system: String? = null,
    val messages: List<ChatMessageIn>,
    val tools: List<OpenAiTool>? = null,
    val temperature: Float? = null,
    val top_p: Float? = null,
    val top_k: Int? = null,
    val stream: Boolean = false,
)

@Serializable
data class AnthropicMessagesResponse(
    val id: String,
    val type: String = "message",
    val role: String = "assistant",
    val model: String,
    val content: List<AnthropicContentBlock>,
    val stop_reason: String? = null,
    val usage: AnthropicUsage = AnthropicUsage(),
)

@Serializable
data class AnthropicContentBlock(
    val type: String = "text",
    val text: String,
)

@Serializable
data class AnthropicUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
)
