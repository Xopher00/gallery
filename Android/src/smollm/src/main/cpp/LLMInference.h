#ifndef LLMINFERENCE_H
#define LLMINFERENCE_H

#include "llama.h"
#include "common.h"
#include "ggml.h"
#include <string>
#include <vector>

class LLMInference {
public:
    // One benchmark repetition. Callers repeat and aggregate, so nothing is averaged here.
    struct BenchResult {
        double prefill_seconds;
        double decode_seconds;
        double prefill_tokens_per_second;
        double decode_tokens_per_second;
    };

    void loadModel(const char *model_path, float minP, float temperature, float topP, int topK,
                   float repeatPenalty, bool storeChats, long contextSize, const char *chatTemplate,
                   int nThreads, bool useMmap, bool useMlock);
    void addChatMessage(const char *message, const char *role);
    float getResponseGenerationTime() const;
    int getContextSizeUsed() const;
    void startCompletion(const char *query);
    std::string completionLoop();
    void stopCompletion();
    BenchResult benchModel(int pp, int tg, int pl);
    std::vector<float> getEmbedding(const char *text);
    ~LLMInference();

private:
    llama_model *_model = nullptr;
    llama_context *_ctx = nullptr;
    llama_sampler *_sampler = nullptr;
    llama_batch *_batch = nullptr;
    llama_token _currToken;

    std::vector<llama_chat_message> _messages;
    std::vector<char> _formattedMessages;
    std::vector<llama_token> _promptTokens;
    std::string _response;
    std::string _cacheResponseTokens;
    const char *_chatTemplate = nullptr;
    bool _storeChats = true;
    std::string _assistantRole = "assistant";

    int64_t _responseGenerationTime = 0;
    int _responseNumTokens = 0;
    int _nCtxUsed = 0;

    // Set from the GGUF's own pooling-type metadata; getEmbedding() refuses to run otherwise.
    bool _isEmbeddingModel = false;

    static bool _isValidUtf8(const char *response);
};

#endif // LLMINFERENCE_H
