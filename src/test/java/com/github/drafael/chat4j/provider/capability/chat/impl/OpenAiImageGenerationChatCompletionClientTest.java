package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.GeneratedImageAttachmentWriter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAiImageGenerationChatCompletionClientTest {

    @Test
    @DisplayName("OpenAI image model detection includes GPT image models only")
    void isImageModelId_whenModelIdsVary_detectsOnlyOpenAiImageModels() {
        assertThat(OpenAiImageGenerationChatCompletionClient.isImageModelId("gpt-image-1")).isTrue();
        assertThat(OpenAiImageGenerationChatCompletionClient.isImageModelId("gpt-image-1.5")).isTrue();
        assertThat(OpenAiImageGenerationChatCompletionClient.isImageModelId("gpt-image-1-mini")).isTrue();
        assertThat(OpenAiImageGenerationChatCompletionClient.isImageModelId("chatgpt-image-latest")).isTrue();
        assertThat(OpenAiImageGenerationChatCompletionClient.isImageModelId("gpt-5.1")).isFalse();
        assertThat(OpenAiImageGenerationChatCompletionClient.isOpenRouterImageOutputModelId("openai/gpt-5.4-image-2")).isTrue();
    }

    @Test
    @DisplayName("Image intent detection accepts direct image generation requests")
    void hasImageIntent_whenLatestUserMessageAsksForImage_returnsTrue() {
        assertThat(OpenAiImageGenerationChatCompletionClient.hasImageIntent(List.of(Message.user("Draw an image of a tabby cat.")))).isTrue();
        assertThat(OpenAiImageGenerationChatCompletionClient.hasImageIntent(List.of(Message.user("Draw a tabby cat wearing a scarf.")))).isTrue();
        assertThat(OpenAiImageGenerationChatCompletionClient.hasImageIntent(List.of(Message.user("Please render a photo-realistic scene of Mars.")))).isTrue();
        assertThat(OpenAiImageGenerationChatCompletionClient.hasImageIntent(List.of(Message.user("Can you edit this image and replace the background?")))).isTrue();
    }

    @Test
    @DisplayName("Image intent detection excludes renderable source requests")
    void hasImageIntent_whenRequestTargetsRenderableSource_returnsFalse() {
        assertThat(OpenAiImageGenerationChatCompletionClient.hasImageIntent(List.of(Message.user("Draw a Mermaid sequence diagram for login.")))).isFalse();
        assertThat(OpenAiImageGenerationChatCompletionClient.hasImageIntent(List.of(Message.user("Render this LaTeX equation: $E=mc^2$.")))).isFalse();
        assertThat(OpenAiImageGenerationChatCompletionClient.hasImageIntent(List.of(Message.user("Draw the molecule as SMILES.")))).isFalse();
        assertThat(OpenAiImageGenerationChatCompletionClient.hasImageIntent(List.of(Message.user("Create Graphviz DOT code for the flow.")))).isFalse();
        assertThat(OpenAiImageGenerationChatCompletionClient.hasImageIntent(List.of(Message.user("Make an ASCII diagram of the network.")))).isFalse();
    }

    @Test
    @DisplayName("Direct Images API response emits generated image part")
    void streamCompletion_whenDirectImageModelReturnsB64Json_emitsGeneratedImagePart() throws Exception {
        byte[] imageBytes = "fake-image".getBytes(StandardCharsets.UTF_8);
        String responseBody = """
                {"data":[{"b64_json":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(imageBytes));
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = createServer("/v1/images/generations", responseBody, requestBody, 200);
        server.start();
        try {
            var attachment = attachment("generated.png", imageBytes.length);
            GeneratedImageAttachmentWriter imageWriter = mock(GeneratedImageAttachmentWriter.class);
            when(imageWriter.write(any(byte[].class), eq("image/png"))).thenReturn(attachment);
            var subject = new OpenAiImageGenerationChatCompletionClient(noopFallback(), HttpClient.newHttpClient(), imageWriter);
            List<GeneratedImagePart> images = new ArrayList<>();

            subject.streamCompletion(
                    runtime(endpoint(server), "gpt-image-1"),
                    List.of(Message.user("Draw a mountain cabin.")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    token -> {
                    },
                    token -> {
                    },
                    part -> {
                        if (part instanceof GeneratedImagePart generatedImagePart) {
                            images.add(generatedImagePart);
                        }
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(images).containsExactly(new GeneratedImagePart(attachment, null, null, "Generated image"));
            assertThat(requestBody.get()).contains("\"model\":\"gpt-image-1\"");
            assertThat(requestBody.get()).contains("Draw a mountain cabin.");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Responses image_generation tool emits text and generated image parts")
    void streamCompletion_whenChatModelImageToolReturnsOutput_emitsTextAndGeneratedImagePart() throws Exception {
        byte[] imageBytes = "tool-image".getBytes(StandardCharsets.UTF_8);
        byte[] previousImageBytes = "previous-image".getBytes(StandardCharsets.UTF_8);
        var previousImage = Files.createTempFile("chat4j-previous-image", ".png");
        Files.write(previousImage, previousImageBytes);
        String responseBody = """
                {
                  "output": [
                    {"type":"message","content":[{"type":"output_text","text":"Edited image:"}]},
                    {"type":"image_generation_call","result":"%s"}
                  ]
                }
                """.formatted(Base64.getEncoder().encodeToString(imageBytes));
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = createServer("/v1/responses", responseBody, requestBody, 200);
        server.start();
        try {
            var attachment = attachment("generated.png", imageBytes.length);
            GeneratedImageAttachmentWriter imageWriter = mock(GeneratedImageAttachmentWriter.class);
            when(imageWriter.write(any(byte[].class), eq("image/png"))).thenReturn(attachment);
            var subject = new OpenAiImageGenerationChatCompletionClient(noopFallback(), HttpClient.newHttpClient(), imageWriter);
            List<String> tokens = new ArrayList<>();
            List<GeneratedImagePart> images = new ArrayList<>();
            var previousAttachment = new AttachmentRef(UUID.randomUUID(), previousImage.toString(), "previous.png", "image/png", previousImageBytes.length, "sha");

            subject.streamCompletion(
                    runtime(endpoint(server), "gpt-5.1"),
                    List.of(
                            Message.user("Draw an image of a cat."),
                            Message.assistant(List.of(new GeneratedImagePart(previousAttachment, null, null, "Generated image"))),
                            Message.user("Make it watercolor.")
                    ),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    tokens::add,
                    token -> {
                    },
                    part -> {
                        if (part instanceof GeneratedImagePart generatedImagePart) {
                            images.add(generatedImagePart);
                        }
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(tokens).containsExactly("Edited image:");
            assertThat(images).containsExactly(new GeneratedImagePart(attachment, null, null, "Generated image"));
            assertThat(requestBody.get()).contains("\"type\":\"image_generation\"");
            assertThat(requestBody.get()).contains("\"action\":\"auto\"");
            assertThat(requestBody.get()).contains("data:image/png;base64,%s".formatted(Base64.getEncoder().encodeToString(previousImageBytes)));
        } finally {
            server.stop(0);
            Files.deleteIfExists(previousImage);
        }
    }

    @Test
    @DisplayName("Missing previous generated image files are skipped")
    void streamCompletion_whenPreviousGeneratedImageFileMissing_skipsImageInput() throws Exception {
        String responseBody = """
                {"output":[{"type":"message","content":[{"type":"output_text","text":"No image used."}]}]}
                """;
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = createServer("/v1/responses", responseBody, requestBody, 200);
        server.start();
        try {
            var subject = new OpenAiImageGenerationChatCompletionClient(noopFallback(), HttpClient.newHttpClient(), mock(GeneratedImageAttachmentWriter.class));
            var missingAttachment = new AttachmentRef(UUID.randomUUID(), "/tmp/chat4j-missing-generated-image.png", "missing.png", "image/png", 42L, "sha");

            subject.streamCompletion(
                    runtime(endpoint(server), "gpt-5.1"),
                    List.of(
                            Message.assistant(List.of(new GeneratedImagePart(missingAttachment, null, null, "Generated image"))),
                            Message.user("Make it realistic.")
                    ),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    token -> {
                    },
                    token -> {
                    },
                    part -> {
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(requestBody.get()).doesNotContain("input_image");
            assertThat(requestBody.get()).doesNotContain("missing.png");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("OpenRouter image models request modalities and emit generated image parts")
    void streamCompletion_whenOpenRouterImageModelReturnsImages_emitsGeneratedImagePart() throws Exception {
        byte[] imageBytes = "openrouter-image".getBytes(StandardCharsets.UTF_8);
        String dataUrl = "data:image/png;base64,%s".formatted(Base64.getEncoder().encodeToString(imageBytes));
        String responseBody = """
                {
                  "choices": [{
                    "message": {
                      "role": "assistant",
                      "content": "Generated image:",
                      "images": [{"image_url": {"url": "%s"}}]
                    }
                  }]
                }
                """.formatted(dataUrl);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = createServer("/v1/chat/completions", responseBody, requestBody, 200);
        server.start();
        try {
            var attachment = attachment("openrouter-generated.png", imageBytes.length);
            GeneratedImageAttachmentWriter imageWriter = mock(GeneratedImageAttachmentWriter.class);
            when(imageWriter.write(any(byte[].class), eq("image/png"))).thenReturn(attachment);
            var subject = new OpenAiImageGenerationChatCompletionClient(noopFallback(), HttpClient.newHttpClient(), imageWriter);
            List<String> tokens = new ArrayList<>();
            List<GeneratedImagePart> images = new ArrayList<>();

            subject.streamCompletion(
                    runtime(endpoint(server), "openai/gpt-5.4-image-2", "OpenRouter"),
                    List.of(Message.user("Generate image of a rainy city bus scene.")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    tokens::add,
                    token -> {
                    },
                    part -> {
                        if (part instanceof GeneratedImagePart generatedImagePart) {
                            images.add(generatedImagePart);
                        }
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(tokens).containsExactly("Generated image:");
            assertThat(images).containsExactly(new GeneratedImagePart(attachment, null, null, "Generated image"));
            assertThat(requestBody.get()).contains("\"modalities\":[\"image\",\"text\"]");
            assertThat(requestBody.get()).contains("\"model\":\"openai/gpt-5.4-image-2\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("OpenRouter chat models with image intent use image generation server tool")
    void streamCompletion_whenOpenRouterChatModelHasImageIntent_usesImageGenerationServerTool() throws Exception {
        byte[] imageBytes = "openrouter-tool-image".getBytes(StandardCharsets.UTF_8);
        String dataUrl = "data:image/png;base64,%s".formatted(Base64.getEncoder().encodeToString(imageBytes));
        String responseBody = """
                {"choices":[{"message":{"content":"Generated image:","images":[{"image_url":{"url":"%s"}}]}}]}
                """.formatted(dataUrl);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = createServer("/v1/chat/completions", responseBody, requestBody, 200);
        server.start();
        try {
            var attachment = attachment("openrouter-tool-generated.png", imageBytes.length);
            GeneratedImageAttachmentWriter imageWriter = mock(GeneratedImageAttachmentWriter.class);
            when(imageWriter.write(any(byte[].class), eq("image/png"))).thenReturn(attachment);
            var subject = new OpenAiImageGenerationChatCompletionClient(noopFallback(), HttpClient.newHttpClient(), imageWriter);
            List<String> tokens = new ArrayList<>();
            List<GeneratedImagePart> images = new ArrayList<>();

            subject.streamCompletion(
                    runtime(endpoint(server), "openai/gpt-5.5", "OpenRouter"),
                    List.of(Message.user("Generate image of a rainy city bus scene.")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    tokens::add,
                    token -> {
                    },
                    part -> {
                        if (part instanceof GeneratedImagePart generatedImagePart) {
                            images.add(generatedImagePart);
                        }
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(tokens).containsExactly("Generated image:");
            assertThat(images).containsExactly(new GeneratedImagePart(attachment, null, null, "Generated image"));
            assertThat(requestBody.get()).contains("\"type\":\"openrouter:image_generation\"");
            assertThat(requestBody.get()).contains("\"tool_choice\":\"auto\"");
            assertThat(requestBody.get()).doesNotContain("\"modalities\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("OpenRouter chat model emits warning and model text when image tool is not called")
    void streamCompletion_whenOpenRouterImageIntentReturnsNoImage_emitsWarningAndModelResponse() throws Exception {
        String responseBody = """
                {"choices":[{"message":{"content":"Here is a polished image-generation prompt."}}]}
                """;
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = createServer("/v1/chat/completions", responseBody, requestBody, 200);
        server.start();
        try {
            var subject = new OpenAiImageGenerationChatCompletionClient(noopFallback(), HttpClient.newHttpClient(), mock(GeneratedImageAttachmentWriter.class));
            List<String> tokens = new ArrayList<>();
            List<GeneratedImagePart> images = new ArrayList<>();

            subject.streamCompletion(
                    runtime(endpoint(server), "openai/gpt-5.5", "OpenRouter"),
                    List.of(Message.user("Generate image of a rainy city bus scene.")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    tokens::add,
                    token -> {
                    },
                    part -> {
                        if (part instanceof GeneratedImagePart generatedImagePart) {
                            images.add(generatedImagePart);
                        }
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(tokens).containsExactly(
                    "OpenRouter image generation tool was not called; showing the model response instead.\n\n",
                    "Here is a polished image-generation prompt."
            );
            assertThat(images).isEmpty();
            assertThat(requestBody.get()).contains("openrouter:image_generation");
            assertThat(requestBody.get()).doesNotContain("\"model\":\"openai/gpt-5-image\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("OpenRouter image-only models request only image modality")
    void streamCompletion_whenOpenRouterImageOnlyModelReturnsImages_requestsOnlyImageModality() throws Exception {
        byte[] imageBytes = "flux-image".getBytes(StandardCharsets.UTF_8);
        String dataUrl = "data:image/png;base64,%s".formatted(Base64.getEncoder().encodeToString(imageBytes));
        String responseBody = """
                {"choices":[{"message":{"images":[{"image_url":{"url":"%s"}}]}}]}
                """.formatted(dataUrl);
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = createServer("/v1/chat/completions", responseBody, requestBody, 200);
        server.start();
        try {
            var attachment = attachment("flux-generated.png", imageBytes.length);
            GeneratedImageAttachmentWriter imageWriter = mock(GeneratedImageAttachmentWriter.class);
            when(imageWriter.write(any(byte[].class), eq("image/png"))).thenReturn(attachment);
            var subject = new OpenAiImageGenerationChatCompletionClient(noopFallback(), HttpClient.newHttpClient(), imageWriter);

            subject.streamCompletion(
                    runtime(endpoint(server), "black-forest-labs/flux.2-pro", "OpenRouter"),
                    List.of(Message.user("Generate image of mountains.")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    token -> {
                    },
                    token -> {
                    },
                    part -> {
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(requestBody.get()).contains("\"modalities\":[\"image\"]");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("OpenAI Codex image prompts use Responses image_generation before CLI fallback")
    void streamCompletion_whenCodexPromptHasImageIntent_usesResponsesImageTool() throws Exception {
        byte[] imageBytes = "codex-image".getBytes(StandardCharsets.UTF_8);
        String responseBody = """
                {"output":[{"type":"image_generation_call","result":"%s"}]}
                """.formatted(Base64.getEncoder().encodeToString(imageBytes));
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = createServer("/v1/responses", responseBody, requestBody, 200);
        server.start();
        try {
            var attachment = attachment("codex-generated.png", imageBytes.length);
            GeneratedImageAttachmentWriter imageWriter = mock(GeneratedImageAttachmentWriter.class);
            when(imageWriter.write(any(byte[].class), eq("image/png"))).thenReturn(attachment);
            var subject = new OpenAiImageGenerationChatCompletionClient(noopFallback(), HttpClient.newHttpClient(), imageWriter);
            List<GeneratedImagePart> images = new ArrayList<>();

            subject.streamCompletion(
                    runtime(endpoint(server), "gpt-5.5", "OpenAI Codex"),
                    List.of(Message.user("Generate image of a rainy city bus scene.")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    token -> {
                    },
                    token -> {
                    },
                    part -> {
                        if (part instanceof GeneratedImagePart generatedImagePart) {
                            images.add(generatedImagePart);
                        }
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(images).containsExactly(new GeneratedImagePart(attachment, null, null, "Generated image"));
            assertThat(requestBody.get()).contains("\"type\":\"image_generation\"");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Non-image chat prompts delegate to fallback client")
    void streamCompletion_whenPromptHasNoImageIntent_delegatesToFallbackClient() throws Exception {
        AtomicBoolean fallbackCalled = new AtomicBoolean(false);
        ChatCompletionClient fallback = fallback(fallbackCalled);
        var subject = new OpenAiImageGenerationChatCompletionClient(fallback, HttpClient.newHttpClient(), mock(GeneratedImageAttachmentWriter.class));

        subject.streamCompletion(
                runtime("http://localhost:1/v1", "gpt-5.1"),
                List.of(Message.user("Explain how binary search works.")),
                ReasoningLevel.OFF,
                WebSearchRequestOptions.disabled(),
                token -> {
                },
                token -> {
                },
                part -> {
                },
                () -> false,
                stream -> {
                },
                () -> {
                }
        );

        assertThat(fallbackCalled).isTrue();
    }

    @Test
    @DisplayName("Unsupported image_generation tool errors fall back to normal chat")
    void streamCompletion_whenImageToolUnsupported_delegatesToFallbackClient() throws Exception {
        String responseBody = """
                {"error":{"message":"The image_generation tool is not supported by this model."}}
                """;
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = createServer("/v1/responses", responseBody, requestBody, 400);
        server.start();
        try {
            AtomicBoolean fallbackCalled = new AtomicBoolean(false);
            var subject = new OpenAiImageGenerationChatCompletionClient(fallback(fallbackCalled), HttpClient.newHttpClient(), mock(GeneratedImageAttachmentWriter.class));

            subject.streamCompletion(
                    runtime(endpoint(server), "gpt-5.1"),
                    List.of(Message.user("Generate an image of a robot.")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    token -> {
                    },
                    token -> {
                    },
                    part -> {
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(fallbackCalled).isTrue();
            assertThat(requestBody.get()).contains("image_generation");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Missing Responses API scopes fall back to normal chat")
    void streamCompletion_whenResponsesScopeIsMissing_delegatesToFallbackClient() throws Exception {
        String responseBody = """
                {"error":{"message":"You have insufficient permissions for this operation. Missing scopes: api.responses.write."}}
                """;
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = createServer("/v1/responses", responseBody, requestBody, 401);
        server.start();
        try {
            AtomicBoolean fallbackCalled = new AtomicBoolean(false);
            var subject = new OpenAiImageGenerationChatCompletionClient(fallback(fallbackCalled), HttpClient.newHttpClient(), mock(GeneratedImageAttachmentWriter.class));

            subject.streamCompletion(
                    runtime(endpoint(server), "gpt-5.5", "OpenAI Codex"),
                    List.of(Message.user("Generate image of a rainy city bus scene.")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    token -> {
                    },
                    token -> {
                    },
                    part -> {
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(fallbackCalled).isTrue();
            assertThat(requestBody.get()).contains("image_generation");
        } finally {
            server.stop(0);
        }
    }

    private HttpServer createServer(String path, String responseBody, AtomicReference<String> requestBody, int statusCode) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        return server;
    }

    private String endpoint(HttpServer server) {
        return "http://127.0.0.1:%d/v1".formatted(server.getAddress().getPort());
    }

    private ProviderRuntime runtime(String baseUrl, String model) {
        return runtime(baseUrl, model, "OpenAI");
    }

    private ProviderRuntime runtime(String baseUrl, String model, String providerName) {
        var descriptor = new ProviderDescriptor(
                providerName,
                AuthType.ENV_VAR,
                "OPENAI_API_KEY",
                null,
                "https://api.openai.com/v1",
                List.of(),
                ProviderCapabilities.chatModelsAndImages(),
                value -> value
        );
        return new ProviderRuntime(descriptor, "OPENAI_API_KEY", baseUrl, "test-key", model);
    }

    private AttachmentRef attachment(String name, long sizeBytes) {
        return new AttachmentRef(UUID.randomUUID(), "/tmp/%s".formatted(name), name, "image/png", sizeBytes, "sha");
    }

    private ChatCompletionClient noopFallback() {
        return fallback(new AtomicBoolean(false));
    }

    private ChatCompletionClient fallback(AtomicBoolean called) {
        return new ChatCompletionClient() {
            @Override
            public void streamCompletion(
                    ProviderRuntime runtime,
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    BooleanSupplier isCancelled,
                    Consumer<AutoCloseable> registerActiveStream,
                    Runnable clearActiveStream
            ) {
                called.set(true);
            }

            @Override
            public void streamCompletion(
                    ProviderRuntime runtime,
                    List<Message> history,
                    ReasoningLevel reasoningLevel,
                    WebSearchRequestOptions webSearchOptions,
                    Consumer<String> onToken,
                    Consumer<String> onThinkingToken,
                    Consumer<ContentPart> onPart,
                    BooleanSupplier isCancelled,
                    Consumer<AutoCloseable> registerActiveStream,
                    Runnable clearActiveStream
            ) {
                called.set(true);
            }
        };
    }
}
