package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

final class ProviderCapabilityHints {

    private ProviderCapabilityHints() {
    }

    static final Set<String> IMAGE_PROVIDER_HINTS = Set.of(
            "anthropic",
            "openai",
            "google ai",
            "openrouter",
            "ollama",
            "lm studio",
            "lmstudio"
    );
    static final Set<String> PERPLEXITY_PROVIDER_HINTS = Set.of("perplexity");
    static final Set<String> DEEPSEEK_PROVIDER_HINTS = Set.of("deepseek");
    static final Set<String> DEEPSEEK_REASONING_MODEL_ALLOW_HINTS = Set.of(
            "deepseek-v4",
            "deepseek-reasoner",
            "deepseek-r1"
    );
    static final Set<String> DEEPSEEK_TOOL_MODEL_ALLOW_HINTS = Set.of(
            "deepseek-v4",
            "deepseek-chat",
            "deepseek-reasoner"
    );
    static final Set<String> ANTHROPIC_NATIVE_WEB_SEARCH_PROVIDER_HINTS = Set.of("anthropic");
    static final Set<String> OPENAI_NATIVE_WEB_SEARCH_PROVIDER_HINTS = Set.of("openai");
    static final Set<String> XAI_NATIVE_WEB_SEARCH_PROVIDER_HINTS = Set.of("xai");
    static final Set<String> GROQ_NATIVE_WEB_SEARCH_PROVIDER_HINTS = Set.of("groq");
    static final Set<String> GOOGLE_NATIVE_WEB_SEARCH_PROVIDER_HINTS = Set.of("google ai", "google", "gemini");
    static final Set<String> OPENROUTER_PROVIDER_HINTS = Set.of("openrouter");
    static final Set<String> NATIVE_WEB_SEARCH_MODEL_DENY_HINTS = Set.of(
            "embedding",
            "moderation",
            "whisper",
            "transcribe",
            "tts",
            "speech",
            "image"
    );
    static final Set<String> ANTHROPIC_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS = Set.of("claude");
    static final Set<String> OPENAI_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS = Set.of(
            "search-preview",
            "gpt-4o-search",
            "gpt-4.1",
            "gpt-5",
            "o3",
            "o4"
    );
    static final Set<String> XAI_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS = Set.of(
            "grok-3",
            "grok-4"
    );
    static final Set<String> GOOGLE_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS = Set.of(
            "gemini-2",
            "gemini-3"
    );
    static final Set<String> OPENROUTER_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS = Set.of(":online");
    static final Set<String> OLLAMA_PROVIDER_HINTS = Set.of("ollama");
    static final Set<String> LM_STUDIO_PROVIDER_HINTS = Set.of("lm studio", "lmstudio");
    static final Set<String> GOOGLE_AI_PROVIDER_HINTS = Set.of("google ai", "google", "gemini");
    static final Set<String> REASONING_PROVIDER_HINTS = Set.of(
            "anthropic",
            "openai",
            "google ai",
            "openrouter",
            "groq",
            "deepseek",
            "mistral",
            "xai",
            "ollama",
            "lm studio",
            "lmstudio"
    );
    static final Set<String> IMAGE_MODEL_ALLOW_HINTS = Set.of(
            "vision",
            "gpt-4o",
            "gpt-4.1",
            "gpt-4.5",
            "gpt-5",
            "gemini",
            "claude",
            "sonnet",
            "opus",
            "haiku",
            "llava",
            "bakllava",
            "moondream",
            "minicpm-v",
            "qwen-vl",
            "qwen2-vl",
            "qwen2.5-vl",
            "pixtral",
            "llama3.2-vision",
            "gemma3",
            "gemma4",
            "gemma-4"
    );
    static final Set<String> IMAGE_MODEL_DENY_HINTS = Set.of("codex", "whisper", "embedding", "moderation", "tts");
    static final Set<String> REASONING_MODEL_ALLOW_HINTS = Set.of(
            "reasoning",
            "thinking",
            "think",
            "o1",
            "o3",
            "o4",
            "gpt-5",
            "gpt-oss",
            "r1",
            "qwq",
            "qwen3",
            "deepseek-r1",
            "magistral",
            "grok-3",
            "claude",
            "sonnet",
            "opus",
            "haiku",
            "gemini-2.5",
            "gemini-3",
            "reasoner",
            "deepseek-reasoner"
    );
    static final Set<String> REASONING_MODEL_DENY_HINTS = Set.of(
            "embedding",
            "moderation",
            "whisper",
            "transcribe",
            "tts",
            "speech",
            "image"
    );
    static final Set<String> DYNAMIC_IMAGE_HINTS = Set.of("image", "vision", "multimodal", "image_input", "vision_input");
    static final Set<String> DYNAMIC_TEXT_ONLY_HINTS = Set.of(
            "text-only",
            "text_only",
            "no-image",
            "no_image",
            "without-image"
    );
    static final Set<String> DYNAMIC_REASONING_HINTS = Set.of(
            "reasoning",
            "thinking",
            "chain_of_thought",
            "cot",
            "reasoning_effort",
            "include_reasoning"
    );
    static final Set<String> DYNAMIC_NON_REASONING_HINTS = Set.of(
            "no-reasoning",
            "no_reasoning",
            "without-reasoning",
            "non-reasoning",
            "non_reasoning",
            "reasoning-disabled"
    );
    static final Set<String> TOOL_PROVIDER_HINTS = Set.of(
            "anthropic",
            "openai",
            "openrouter",
            "google ai",
            "google",
            "gemini",
            "groq",
            "mistral",
            "xai",
            "deepseek",
            "copilot"
    );
    static final Set<String> TOOL_MODEL_ALLOW_HINTS = Set.of(
            "gpt-4",
            "gpt-5",
            "o1",
            "o3",
            "o4",
            "claude",
            "sonnet",
            "opus",
            "haiku",
            "gemini",
            "grok",
            "deepseek-chat",
            "codex",
            "mistral",
            "ministral",
            "devstral"
    );
    static final Set<String> TOOL_MODEL_DENY_HINTS = Set.of(
            "embedding",
            "whisper",
            "moderation",
            "transcribe",
            "tts",
            "speech",
            "image",
            "vision"
    );
    static final Set<String> DYNAMIC_TOOL_HINTS = Set.of(
            "tool",
            "tool_use",
            "tool-use",
            "function",
            "function_calling",
            "function-calling",
            "tool_choice",
            "tool-choice",
            "parallel_tool_calls",
            "parallel-tool-calls",
            "computer_use",
            "computer-use",
            "web_search",
            "web-search"
    );
    static final Set<String> DYNAMIC_NON_TOOL_HINTS = Set.of(
            "no-tools",
            "no_tools",
            "without-tools",
            "without_tools",
            "tool-disabled",
            "tools-disabled",
            "none"
    );
    static final Set<String> DYNAMIC_NATIVE_WEB_SEARCH_HINTS = Set.of(
            "web_search",
            "web-search",
            "web search",
            "web_browsing",
            "web-browsing",
            "web browsing",
            "browser_search",
            "browser-search",
            "browsing",
            "grounding",
            "google_search",
            "google-search",
            "google search"
    );
    static final Set<String> DYNAMIC_NON_NATIVE_WEB_SEARCH_HINTS = Set.of(
            "no-web-search",
            "no_web_search",
            "without-web-search",
            "without_web_search",
            "web-search-disabled",
            "web_search_disabled",
            "search-disabled",
            "search_disabled",
            "none"
    );
    static final Set<String> NATIVE_WEB_SEARCH_BOOLEAN_FIELDS = Set.of(
            "web_search",
            "webSearch",
            "supports_web_search",
            "supportsWebSearch",
            "native_web_search",
            "nativeWebSearch",
            "supports_native_web_search",
            "supportsNativeWebSearch",
            "web_browsing",
            "webBrowsing",
            "supports_web_browsing",
            "supportsWebBrowsing",
            "grounding",
            "supports_grounding",
            "supportsGrounding",
            "google_search",
            "googleSearch",
            "supports_google_search",
            "supportsGoogleSearch"
    );
    static final Set<String> TOOL_BOOLEAN_FIELDS = Set.of(
            "tools",
            "supports_tools",
            "supportsTools",
            "tool_use",
            "toolUse",
            "supports_tool_use",
            "supportsToolUse",
            "function_calling",
            "functionCalling",
            "supports_function_calling",
            "supportsFunctionCalling",
            "tool_calling",
            "toolCalling",
            "supports_tool_calling",
            "supportsToolCalling"
    );
    static final Set<String> CAPABILITY_BOOLEAN_FIELDS = Set.of(
            "vision",
            "supports_vision",
            "supportsVision",
            "image_input",
            "supports_image_input",
            "imageInput",
            "supportsImageInput",
            "multimodal",
            "supports_multimodal",
            "supportsMultimodal"
    );
    static final Set<String> REASONING_BOOLEAN_FIELDS = Set.of(
            "reasoning",
            "supports_reasoning",
            "supportsReasoning",
            "thinking",
            "supports_thinking",
            "supportsThinking"
    );
    static final Set<String> OLLAMA_MODELFAMILY_VISION_HINTS = Set.of(
            "vision",
            "mllama",
            "llava",
            "bakllava",
            "gemma3",
            "gemma4",
            "qwen2-vl",
            "qwen2.5-vl"
    );
    static final Set<String> OLLAMA_MODELINFO_FIELD_VISION_HINTS = Set.of("vision", "clip", "projector");
    static final Set<String> OLLAMA_MODELINFO_TEXT_VISION_HINTS = Set.of("projector", "vision encoder");


    static boolean supportsOpenRouterNativeWebSearch(String model) {
        return containsAny(model, OPENROUTER_NATIVE_WEB_SEARCH_MODEL_ALLOW_HINTS)
                || PerplexityModelIds.isNamespacedSonarModel(model);
    }

    static boolean supportsGroqNativeWebSearch(String model) {
        return StringUtils.equalsAny(model, "compound", "compound-mini", "groq/compound", "groq/compound-mini");
    }

    static boolean supportsDeepSeekReasoning(String model) {
        return StringUtils.isNotBlank(model) && containsAny(model, DEEPSEEK_REASONING_MODEL_ALLOW_HINTS);
    }

    static boolean supportsDeepSeekToolInvocation(String model) {
        return StringUtils.isNotBlank(model) && containsAny(model, DEEPSEEK_TOOL_MODEL_ALLOW_HINTS);
    }


    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    static boolean containsAny(String value, Set<String> hints) {
        return hints.stream().anyMatch(value::contains);
    }

}
