package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleAiGenerateContentClientTest {

    @Test
    @DisplayName("Image model detection includes Gemini image preview models")
    void isImageOutputModelId_whenGeminiImagePreview_returnsTrue() {
        assertThat(GoogleAiGenerateContentClient.isImageOutputModelId("gemini-3-pro-image-preview")).isTrue();
        assertThat(GoogleAiGenerateContentClient.isImageOutputModelId("nano-banana-pro-preview")).isTrue();
        assertThat(GoogleAiGenerateContentClient.isImageOutputModelId("gemini-3-pro")).isFalse();
    }

    @Test
    @DisplayName("Google native response emits text and generated image parts")
    void streamCompletion_whenGoogleReturnsTextAndInlineImage_emitsTextAndGeneratedImagePart() throws Exception {
        byte[] imageBytes = "fake-image".getBytes(StandardCharsets.UTF_8);
        String responseBody = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [
                        {"thought": true, "text": "Planning the image."},
                        {"text": "Here it is"},
                        {"inlineData": {"mimeType": "image/jpeg", "data": "%s"}}
                      ]
                    }
                  }]
                }
                """.formatted(Base64.getEncoder().encodeToString(imageBytes));
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-3-pro-image-preview:generateContent", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var attachment = new AttachmentRef(UUID.randomUUID(), "/tmp/generated.jpg", "generated.jpg", "image/jpeg", imageBytes.length, "sha");
            GeneratedImageAttachmentWriter imageWriter = mock(GeneratedImageAttachmentWriter.class);
            when(imageWriter.write(any(byte[].class), eq("image/jpeg"))).thenReturn(attachment);
            var subject = new GoogleAiGenerateContentClient(new OpenAiChatCompletionClient(), HttpClient.newHttpClient(), imageWriter);
            List<String> tokens = new ArrayList<>();
            List<String> thinkingTokens = new ArrayList<>();
            List<GeneratedImagePart> images = new ArrayList<>();

            subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort())),
                    List.of(Message.user("Draw a cat")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    tokens::add,
                    thinkingTokens::add,
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

            assertThat(thinkingTokens).containsExactly("Planning the image.");
            assertThat(tokens).containsExactly("Here it is");
            assertThat(images).containsExactly(new GeneratedImagePart(attachment, null, null, "Generated image"));
            assertThat(requestBody.get()).contains("\"responseModalities\":[\"TEXT\",\"IMAGE\"]");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Google native web search emits grounded citations")
    void streamCompletion_whenGoogleWebSearchReturnsGroundingMetadata_emitsCitations() throws Exception {
        String responseBody = """
                {
                  "candidates": [{
                    "content": {"parts": [{"text": "Grounded answer"}]},
                    "groundingMetadata": {
                      "groundingChunks": [{
                        "web": {"uri": "https://ai.google.dev/gemini-api/docs/google-search", "title": "Grounding with Google Search"}
                      }],
                      "groundingSupports": [{
                        "segment": {"text": "Grounded answer"},
                        "groundingChunkIndices": [0]
                      }]
                    }
                  }]
                }
                """;
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-2.5-flash:generateContent", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var subject = new GoogleAiGenerateContentClient(new OpenAiChatCompletionClient(), HttpClient.newHttpClient(), mock(GeneratedImageAttachmentWriter.class));
            List<String> tokens = new ArrayList<>();
            List<CitationRef> citations = new ArrayList<>();

            subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort()), "gemini-2.5-flash"),
                    List.of(Message.user("Search Google")),
                    ReasoningLevel.OFF,
                    new WebSearchRequestOptions(true, "native"),
                    tokens::add,
                    token -> {
                    },
                    part -> {
                    },
                    citations::add,
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(tokens).containsExactly("Grounded answer");
            assertThat(citations)
                    .singleElement()
                    .satisfies(citation -> {
                        assertThat(citation.number()).isEqualTo(1);
                        assertThat(citation.kind()).isEqualTo(CitationKind.WEB);
                        assertThat(citation.title()).isEqualTo("Grounding with Google Search");
                        assertThat(citation.url()).isEqualTo("https://ai.google.dev/gemini-api/docs/google-search");
                        assertThat(citation.citedText()).isEqualTo("Grounded answer");
                    });
            assertThat(requestBody.get()).contains("\"google_search\":{}");
            assertThat(requestBody.get()).doesNotContain("responseModalities");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Google native web search falls back when Gemini returns only thinking content")
    void streamCompletion_whenGoogleWebSearchReturnsNoAnswerContent_fallsBackWithoutNativeWebSearch() throws Exception {
        String responseBody = """
                {
                  "candidates": [{
                    "finishReason": "STOP",
                    "content": {"parts": [{"thought": true, "text": "I should search first."}]}
                  }],
                  "usageMetadata": {"promptTokenCount": 12, "candidatesTokenCount": 4, "thoughtsTokenCount": 4, "totalTokenCount": 20}
                }
                """;
        AtomicReference<String> requestBody = new AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-3.5-flash:generateContent", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AtomicReference<WebSearchRequestOptions> fallbackOptions = new AtomicReference<>();
            var subject = new GoogleAiGenerateContentClient(fallbackClient(fallbackOptions), HttpClient.newHttpClient(), mock(GeneratedImageAttachmentWriter.class));
            List<String> tokens = new ArrayList<>();
            List<String> thinkingTokens = new ArrayList<>();

            subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort()), "gemini-3.5-flash"),
                    List.of(Message.user("Search Google")),
                    ReasoningLevel.OFF,
                    new WebSearchRequestOptions(true, "native"),
                    tokens::add,
                    thinkingTokens::add,
                    part -> {
                    },
                    citation -> {
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(tokens).containsExactly("Fallback answer");
            assertThat(thinkingTokens).isEmpty();
            assertThat(fallbackOptions.get().enabled()).isFalse();
            assertThat(requestBody.get()).contains("\"google_search\":{}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Google native web search falls back when Gemini filters generated content for recitation")
    void streamCompletion_whenGoogleWebSearchResponseIsRecitationFiltered_fallsBackWithoutNativeWebSearch() throws Exception {
        String responseBody = """
                {
                  "candidates": [{
                    "finishReason": "RECITATION",
                    "finishMessage": "The generated content was filtered because it may contain material that resembles existing copyrighted works."
                  }],
                  "usageMetadata": {"promptTokenCount": 143, "candidatesTokenCount": 0, "thoughtsTokenCount": 1517, "totalTokenCount": 1660}
                }
                """;
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-3.1-pro-preview:generateContent", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            AtomicReference<WebSearchRequestOptions> fallbackOptions = new AtomicReference<>();
            var subject = new GoogleAiGenerateContentClient(fallbackClient(fallbackOptions), HttpClient.newHttpClient(), mock(GeneratedImageAttachmentWriter.class));
            List<String> tokens = new ArrayList<>();

            subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort()), "gemini-3.1-pro-preview"),
                    List.of(Message.user("real examples how to use JNA for java desktop apps")),
                    ReasoningLevel.OFF,
                    new WebSearchRequestOptions(true, "native"),
                    tokens::add,
                    token -> {
                    },
                    part -> {
                    },
                    citation -> {
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            assertThat(tokens).containsExactly("Fallback answer");
            assertThat(fallbackOptions.get().enabled()).isFalse();
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Google native web search reports blocked empty responses without fallback")
    void streamCompletion_whenGoogleWebSearchPromptIsBlocked_reportsBlockReason() throws Exception {
        String responseBody = """
                {
                  "promptFeedback": {"blockReason": "SAFETY"}
                }
                """;
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-2.5-flash:generateContent", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var subject = new GoogleAiGenerateContentClient(failingFallbackClient(), HttpClient.newHttpClient(), mock(GeneratedImageAttachmentWriter.class));

            assertThatThrownBy(() -> subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort()), "gemini-2.5-flash"),
                    List.of(Message.user("Search Google")),
                    ReasoningLevel.OFF,
                    new WebSearchRequestOptions(true, "native"),
                    token -> {
                    },
                    token -> {
                    },
                    part -> {
                    },
                    citation -> {
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no candidate content parts")
                    .hasMessageContaining("promptBlockReason=SAFETY");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Google thinking-only responses fail without emitting partial UI output")
    void streamCompletion_whenGoogleReturnsOnlyThoughtParts_reportsNoAnswerText() throws Exception {
        String responseBody = """
                {
                  "candidates": [{
                    "finishReason": "STOP",
                    "content": {"parts": [{"thought": true, "text": "I should think first."}]}
                  }]
                }
                """;
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-3-pro-image-preview:generateContent", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var subject = new GoogleAiGenerateContentClient(failingFallbackClient(), HttpClient.newHttpClient(), mock(GeneratedImageAttachmentWriter.class));
            List<String> thinkingTokens = new ArrayList<>();

            assertThatThrownBy(() -> subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort())),
                    List.of(Message.user("Draw a cat")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    token -> {
                    },
                    thinkingTokens::add,
                    part -> {
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("only thinking parts, no answer text");
            assertThat(thinkingTokens).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private ChatCompletionClient fallbackClient(AtomicReference<WebSearchRequestOptions> fallbackOptions) {
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
                throw new AssertionError("Expected web-search-aware fallback overload");
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
                    Consumer<CitationRef> onCitation,
                    BooleanSupplier isCancelled,
                    Consumer<AutoCloseable> registerActiveStream,
                    Runnable clearActiveStream
            ) {
                fallbackOptions.set(webSearchOptions);
                onToken.accept("Fallback answer");
            }
        };
    }

    private ChatCompletionClient failingFallbackClient() {
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
                throw new AssertionError("Fallback should not be called");
            }
        };
    }

    private ProviderRuntime runtime(String baseUrl) {
        return runtime(baseUrl, "gemini-3-pro-image-preview");
    }

    private ProviderRuntime runtime(String baseUrl, String model) {
        var descriptor = new ProviderDescriptor(
                "Google AI",
                AuthType.ENV_VAR,
                "GEMINI_API_KEY",
                null,
                "https://generativelanguage.googleapis.com/v1beta/openai",
                List.of(),
                ProviderCapabilities.chatAndModels(),
                value -> value
        );
        return new ProviderRuntime(descriptor, "GEMINI_API_KEY", baseUrl, "test-key", model);
    }
}
