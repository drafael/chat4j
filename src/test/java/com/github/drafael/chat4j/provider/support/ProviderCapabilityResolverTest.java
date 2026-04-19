package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderCapabilityResolverTest {

    private static final String TEST_API_KEY = "test-key";

    @Test
    @DisplayName("OpenAI multimodal-style model names are treated as image-capable")
    void supportsImageInput_whenOpenAiModelLooksMultimodal_returnsTrue() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatAndModels(),
                "OpenAI",
                "gpt-4o-mini"
        );

        assertThat(supported).isTrue();
    }

    @Test
    @DisplayName("Codex-style model names are treated as not image-capable")
    void supportsImageInput_whenModelContainsCodex_returnsFalse() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatAndModels(),
                "OpenAI Codex",
                "codex-mini-latest"
        );

        assertThat(supported).isFalse();
    }

    @Test
    @DisplayName("Provider-level hints alone do not mark a model as image-capable")
    void supportsImageInput_whenModelHasNoImageHints_returnsFalse() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatAndModels(),
                "OpenAI",
                "chat-model-v1"
        );

        assertThat(supported).isFalse();
    }

    @Test
    @DisplayName("Ollama vision model hints mark image capability as supported")
    void supportsImageInput_whenOllamaModelLooksMultimodal_returnsTrue() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatAndModels(),
                "Ollama",
                "gemma4:e4b"
        );

        assertThat(supported).isTrue();
    }

    @Test
    @DisplayName("Ollama text-only model hints keep image capability disabled")
    void supportsImageInput_whenOllamaModelHasNoVisionHints_returnsFalse() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatAndModels(),
                "Ollama",
                "llama3.2:3b"
        );

        assertThat(supported).isFalse();
    }

    @Test
    @DisplayName("Ollama /api/show capability metadata enables image support")
    void supportsImageInput_whenOllamaShowEndpointHasVisionCapability_returnsTrue() throws Exception {
        String responseJson = """
                {
                  "capabilities": ["completion", "vision"]
                }
                """;

        HttpServer server = createOllamaShowServer(responseJson);
        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsImageInput(
                    ProviderCapabilities.chatAndModels(),
                    "Ollama",
                    "my-custom-vision-model",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Explicit Ollama vision=false metadata overrides hint-based image support")
    void supportsImageInput_whenOllamaShowEndpointDeclaresVisionFalse_returnsFalse() throws Exception {
        String responseJson = """
                {
                  "capabilities": {
                    "vision": false
                  }
                }
                """;

        HttpServer server = createOllamaShowServer(responseJson);
        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsImageInput(
                    ProviderCapabilities.chatAndModels(),
                    "Ollama",
                    "gemma4:e4b",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Hint-based image support is used when dynamic probing is unavailable")
    void supportsImageInput_whenOllamaShowEndpointIsUnavailable_returnsTrueFromModelHints() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatAndModels(),
                "Ollama",
                "gemma4:e4b",
                "http://127.0.0.1:9/v1"
        );

        assertThat(supported).isTrue();
    }

    @Test
    @DisplayName("OpenAI-compatible model endpoint metadata enables image support")
    void supportsImageInput_whenModelEndpointDeclaresImageModality_returnsTrue() throws Exception {
        HttpServer server = createOpenAiModelServer(
                "custom-mm",
                200,
                """
                        {
                          "id": "custom-mm",
                          "input_modalities": ["text", "image"]
                        }
                        """,
                "{\"data\": []}"
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsImageInput(
                    ProviderCapabilities.chatAndModels(),
                    "OpenRouter",
                    "custom-mm",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Explicit supports_vision=false metadata overrides hint-based image support")
    void supportsImageInput_whenModelEndpointDeclaresVisionFalse_returnsFalse() throws Exception {
        HttpServer server = createOpenAiModelServer(
                "gpt-4o-mini",
                200,
                """
                        {
                          "id": "gpt-4o-mini",
                          "supports_vision": false
                        }
                        """,
                "{\"data\": []}"
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsImageInput(
                    ProviderCapabilities.chatAndModels(),
                    "OpenAI",
                    "gpt-4o-mini",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Camel-case image capability metadata is recognized")
    void supportsImageInput_whenModelEndpointDeclaresSupportsVisionCamelCase_returnsTrue() throws Exception {
        HttpServer server = createOpenAiModelServer(
                "camel-mm",
                200,
                """
                        {
                          "id": "camel-mm",
                          "supportsVision": true
                        }
                        """,
                "{\"data\": []}"
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsImageInput(
                    ProviderCapabilities.chatAndModels(),
                    "OpenRouter",
                    "camel-mm",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Models list metadata is used when the model endpoint is unavailable")
    void supportsImageInput_whenModelEndpointUnavailable_returnsTrueFromModelsListMetadata() throws Exception {
        HttpServer server = createOpenAiModelServer(
                "list-only-mm",
                404,
                "{}",
                """
                        {
                          "data": [
                            {
                              "id": "list-only-mm",
                              "capabilities": {
                                "input_modalities": ["text", "image"]
                              }
                            }
                          ]
                        }
                        """
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsImageInput(
                    ProviderCapabilities.chatAndModels(),
                    "Mistral",
                    "list-only-mm",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Reasoning hints in model names enable reasoning support")
    void supportsReasoning_whenModelNameMatchesFallbackHints_returnsTrue() {
        boolean supported = ProviderCapabilityResolver.supportsReasoning(
                ProviderCapabilities.chatAndModels(),
                "OpenAI",
                "o3-mini"
        );

        assertThat(supported).isTrue();
    }

    @Test
    @DisplayName("Claude model hints enable reasoning support")
    void supportsReasoning_whenClaudeModelNameIsUsed_returnsTrue() {
        boolean supported = ProviderCapabilityResolver.supportsReasoning(
                ProviderCapabilities.chatAndModels(),
                "Anthropic",
                "claude-sonnet-4-20250514"
        );

        assertThat(supported).isTrue();
    }

    @Test
    @DisplayName("GPT-5 model hints enable image support")
    void supportsImageInput_whenGpt5ModelNameIsUsed_returnsTrue() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatAndModels(),
                "OpenRouter",
                "gpt-5-mini"
        );

        assertThat(supported).isTrue();
    }

    @Test
    @DisplayName("Gemma 4 model hints enable image support")
    void supportsImageInput_whenGemma4ModelNameIsUsed_returnsTrue() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatAndModels(),
                "Google AI",
                "gemma-4-27b-it"
        );

        assertThat(supported).isTrue();
    }

    @Test
    @DisplayName("Gemini 3 model hints enable reasoning support")
    void supportsReasoning_whenGemini3ModelNameIsUsed_returnsTrue() {
        boolean supported = ProviderCapabilityResolver.supportsReasoning(
                ProviderCapabilities.chatAndModels(),
                "Google AI",
                "gemini-3-flash-preview"
        );

        assertThat(supported).isTrue();
    }

    @Test
    @DisplayName("DeepSeek reasoner model hints enable reasoning support")
    void supportsReasoning_whenDeepSeekReasonerModelNameIsUsed_returnsTrue() {
        boolean supported = ProviderCapabilityResolver.supportsReasoning(
                ProviderCapabilities.chatAndModels(),
                "DeepSeek",
                "deepseek-reasoner"
        );

        assertThat(supported).isTrue();
    }

    @Test
    @DisplayName("Non-reasoning model hints disable reasoning support")
    void supportsReasoning_whenModelNameMatchesDenyHints_returnsFalse() {
        boolean supported = ProviderCapabilityResolver.supportsReasoning(
                ProviderCapabilities.chatAndModels(),
                "OpenAI",
                "whisper-1"
        );

        assertThat(supported).isFalse();
    }

    @Test
    @DisplayName("Model endpoint metadata enables reasoning support")
    void supportsReasoning_whenModelEndpointDeclaresReasoning_returnsTrue() throws Exception {
        HttpServer server = createOpenAiModelServer(
                "reasoner-x",
                200,
                """
                        {
                          "id": "reasoner-x",
                          "supports_reasoning": true
                        }
                        """,
                "{\"data\": []}"
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsReasoning(
                    ProviderCapabilities.chatAndModels(),
                    "OpenRouter",
                    "reasoner-x",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Camel-case reasoning capability metadata is recognized")
    void supportsReasoning_whenModelEndpointDeclaresSupportsThinkingCamelCase_returnsTrue() throws Exception {
        HttpServer server = createOpenAiModelServer(
                "camel-rsn",
                200,
                """
                        {
                          "id": "camel-rsn",
                          "supportsThinking": true
                        }
                        """,
                "{\"data\": []}"
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsReasoning(
                    ProviderCapabilities.chatAndModels(),
                    "OpenRouter",
                    "camel-rsn",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Explicit reasoning=false metadata overrides hint-based reasoning support")
    void supportsReasoning_whenModelEndpointDeclaresReasoningFalse_returnsFalse() throws Exception {
        HttpServer server = createOpenAiModelServer(
                "o3-mini",
                200,
                """
                        {
                          "id": "o3-mini",
                          "supports_reasoning": false
                        }
                        """,
                "{\"data\": []}"
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsReasoning(
                    ProviderCapabilities.chatAndModels(),
                    "OpenAI",
                    "o3-mini",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Models list metadata enables reasoning support")
    void supportsReasoning_whenModelsListDeclaresReasoningTag_returnsTrue() throws Exception {
        HttpServer server = createOpenAiModelServer(
                "list-reasoner",
                404,
                "{}",
                """
                        {
                          "data": [
                            {
                              "id": "list-reasoner",
                              "tags": ["reasoning"]
                            }
                          ]
                        }
                        """
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsReasoning(
                    ProviderCapabilities.chatAndModels(),
                    "DeepSeek",
                    "list-reasoner",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("OpenRouter supported_parameters metadata enables reasoning support")
    void supportsReasoning_whenModelEndpointIncludesSupportedParameters_returnsTrue() throws Exception {
        HttpServer server = createOpenAiModelServer(
                "openrouter-reasoner",
                200,
                """
                        {
                          "id": "openrouter-reasoner",
                          "supported_parameters": ["temperature", "reasoning", "reasoning_effort"]
                        }
                        """,
                "{\"data\": []}"
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsReasoning(
                    ProviderCapabilities.chatAndModels(),
                    "OpenRouter",
                    "openrouter-reasoner",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("OpenRouter architecture input_modalities metadata enables image support")
    void supportsImageInput_whenModelEndpointIncludesArchitectureInputModalities_returnsTrue() throws Exception {
        HttpServer server = createOpenAiModelServer(
                "openrouter-vision",
                200,
                """
                        {
                          "id": "openrouter-vision",
                          "architecture": {
                            "input_modalities": ["text", "image"],
                            "output_modalities": ["text"],
                            "modality": "text+image->text"
                          }
                        }
                        """,
                "{\"data\": []}"
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsImageInput(
                    ProviderCapabilities.chatAndModels(),
                    "OpenRouter",
                    "openrouter-vision",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("LM Studio /api/v1/models capability metadata enables image support")
    void supportsImageInput_whenLmStudioModelsEndpointDeclaresVision_returnsTrue() throws Exception {
        HttpServer server = createLmStudioModelsServer(
                """
                        {
                          "models": [
                            {
                              "key": "google/gemma-4-26b-a4b",
                              "display_name": "Gemma 4 26B A4B",
                              "capabilities": {
                                "vision": true
                              }
                            }
                          ]
                        }
                        """
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsImageInput(
                    ProviderCapabilities.chatAndModels(),
                    "LM Studio",
                    "google/gemma-4-26b-a4b",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("LM Studio /api/v1/models reasoning metadata enables reasoning support")
    void supportsReasoning_whenLmStudioModelsEndpointDeclaresReasoning_returnsTrue() throws Exception {
        HttpServer server = createLmStudioModelsServer(
                """
                        {
                          "models": [
                            {
                              "key": "openai/gpt-oss-20b",
                              "display_name": "GPT OSS 20B",
                              "capabilities": {
                                "vision": false,
                                "reasoning": {
                                  "allowed_options": ["off", "on"],
                                  "default": "on"
                                }
                              }
                            }
                          ]
                        }
                        """
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsReasoning(
                    ProviderCapabilities.chatAndModels(),
                    "LM Studio",
                    "openai/gpt-oss-20b",
                    "http://127.0.0.1:%d/v1".formatted(port)
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Google AI native model metadata enables image support")
    void supportsImageInput_whenGoogleAiModelHasImageInputModalities_returnsTrue() throws Exception {
        HttpServer server = createGoogleAiModelsServer(
                "gemini-2.5-flash",
                """
                        {
                          "name": "models/gemini-2.5-flash",
                          "inputModalities": ["TEXT", "IMAGE"],
                          "supportedGenerationMethods": ["generateContent"]
                        }
                        """,
                """
                        {
                          "models": [
                            {
                              "name": "models/gemini-2.5-flash",
                              "inputModalities": ["TEXT", "IMAGE"],
                              "supportedGenerationMethods": ["generateContent"]
                            }
                          ]
                        }
                        """
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsImageInput(
                    ProviderCapabilities.chatAndModels(),
                    "Google AI",
                    "gemini-2.5-flash",
                    "http://127.0.0.1:%d/v1beta/openai".formatted(port),
                    TEST_API_KEY
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Google AI native model metadata enables reasoning support")
    void supportsReasoning_whenGoogleAiModelHasThinkingFlag_returnsTrue() throws Exception {
        HttpServer server = createGoogleAiModelsServer(
                "gemini-2.5-pro",
                """
                        {
                          "name": "models/gemini-2.5-pro",
                          "thinking": true,
                          "supportedGenerationMethods": ["generateContent"]
                        }
                        """,
                """
                        {
                          "models": [
                            {
                              "name": "models/gemini-2.5-pro",
                              "thinking": true,
                              "supportedGenerationMethods": ["generateContent"]
                            }
                          ]
                        }
                        """
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsReasoning(
                    ProviderCapabilities.chatAndModels(),
                    "Google AI",
                    "gemini-2.5-pro",
                    "http://127.0.0.1:%d/v1beta/openai".formatted(port),
                    TEST_API_KEY
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Google AI falls back to OpenAI-compatible metadata when native modality metadata is missing")
    void supportsImageInput_whenGoogleNativeMetadataLacksModalities_returnsTrueFromOpenAiMetadataFallback() throws Exception {
        HttpServer server = createGoogleAiFallbackServer(
                "custom-mm",
                """
                        {
                          "name": "models/custom-mm",
                          "supportedGenerationMethods": ["generateContent"]
                        }
                        """,
                """
                        {
                          "models": [
                            {
                              "name": "models/custom-mm",
                              "supportedGenerationMethods": ["generateContent"]
                            }
                          ]
                        }
                        """,
                """
                        {
                          "id": "custom-mm",
                          "input_modalities": ["text", "image"]
                        }
                        """,
                "{\"data\": []}",
                TEST_API_KEY
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsImageInput(
                    ProviderCapabilities.chatAndModels(),
                    "Google AI",
                    "custom-mm",
                    "http://127.0.0.1:%d/v1beta/openai".formatted(port),
                    TEST_API_KEY
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Google AI falls back to OpenAI-compatible metadata when native thinking metadata is missing")
    void supportsReasoning_whenGoogleNativeMetadataLacksThinking_returnsTrueFromOpenAiMetadataFallback() throws Exception {
        HttpServer server = createGoogleAiFallbackServer(
                "custom-rsn",
                """
                        {
                          "name": "models/custom-rsn",
                          "supportedGenerationMethods": ["generateContent"]
                        }
                        """,
                """
                        {
                          "models": [
                            {
                              "name": "models/custom-rsn",
                              "supportedGenerationMethods": ["generateContent"]
                            }
                          ]
                        }
                        """,
                """
                        {
                          "id": "custom-rsn",
                          "supported_parameters": ["temperature", "reasoning_effort"]
                        }
                        """,
                "{\"data\": []}",
                TEST_API_KEY
        );

        try {
            int port = server.getAddress().getPort();
            boolean supported = ProviderCapabilityResolver.supportsReasoning(
                    ProviderCapabilities.chatAndModels(),
                    "Google AI",
                    "custom-rsn",
                    "http://127.0.0.1:%d/v1beta/openai".formatted(port),
                    TEST_API_KEY
            );

            assertThat(supported).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Provider descriptor image capability flag is honored")
    void supportsImageInput_whenCapabilitiesDeclareImageSupport_returnsTrue() {
        boolean supported = ProviderCapabilityResolver.supportsImageInput(
                ProviderCapabilities.chatModelsAndImages(),
                "Custom Provider",
                "text-only-id"
        );

        assertThat(supported).isTrue();
    }

    @Test
    @DisplayName("File input support remains disabled unless explicitly declared")
    void supportsFileInput_whenCapabilitiesDoNotDeclareSupport_returnsFalse() {
        boolean supported = ProviderCapabilityResolver.supportsFileInput(
                ProviderCapabilities.chatAndModels(),
                "OpenAI",
                "gpt-4o"
        );

        assertThat(supported).isFalse();
    }

    private HttpServer createOllamaShowServer(String responseJson) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/show", exchange -> {
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private HttpServer createOpenAiModelServer(
            String modelId,
            int modelStatusCode,
            String modelResponseJson,
            String listResponseJson
    ) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models/%s".formatted(modelId), exchange -> {
            byte[] body = modelResponseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(modelStatusCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/models", exchange -> {
            byte[] body = listResponseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private HttpServer createGoogleAiModelsServer(
            String modelId,
            String modelResponseJson,
            String listResponseJson
    ) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/%s".formatted(modelId), exchange -> {
            byte[] body = modelResponseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1beta/models", exchange -> {
            byte[] body = listResponseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private HttpServer createGoogleAiFallbackServer(
            String modelId,
            String nativeModelResponseJson,
            String nativeListResponseJson,
            String openAiModelResponseJson,
            String openAiListResponseJson,
            String requiredApiKey
    ) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1beta/models/%s".formatted(modelId), exchange -> {
            byte[] body = nativeModelResponseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1beta/models", exchange -> {
            byte[] body = nativeListResponseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1beta/openai/models/%s".formatted(modelId), exchange -> {
            if (!hasBearerToken(exchange, requiredApiKey)) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }

            byte[] body = openAiModelResponseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1beta/openai/models", exchange -> {
            if (!hasBearerToken(exchange, requiredApiKey)) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }

            byte[] body = openAiListResponseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }

    private boolean hasBearerToken(HttpExchange exchange, String apiKey) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return ("Bearer %s".formatted(apiKey)).equals(authorization);
    }

    private HttpServer createLmStudioModelsServer(String responseJson) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/models", exchange -> {
            byte[] body = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"data\": []}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}
