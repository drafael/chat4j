package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.CitationKind;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import com.github.drafael.chat4j.provider.capability.chat.ChatCompletionClient;
import com.github.drafael.chat4j.provider.core.ProviderRuntime;
import com.github.drafael.chat4j.provider.support.GeneratedImageAttachmentWriter;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentSupport;
import com.github.drafael.chat4j.provider.support.ProviderAttachmentTestSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GoogleAiGenerateContentClientTest {

    @Test
    @DisplayName("Image model detection includes Gemini image preview models")
    void isImageOutputModelId_whenGeminiImagePreview_returnsTrue() {
        assertThat(GoogleAiGenerateContentClient.isImageOutputModelId("gemini-3-pro-image-preview")).isTrue();
        assertThat(GoogleAiGenerateContentClient.isImageOutputModelId("nano-banana-pro-preview")).isTrue();
        assertThat(GoogleAiGenerateContentClient.isImageOutputModelId("gemini-3-pro")).isFalse();
    }

    @Test
    @DisplayName("An interrupted worker does not start a Google native request")
    void streamCompletion_whenWorkerIsInterrupted_doesNotStartHttpRequest() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        var authority = ProviderAttachmentTestSupport.authority();
        var subject = new GoogleAiGenerateContentClient(
                failingFallbackClient(),
                httpClient,
                authority,
                mock(GeneratedImageAttachmentWriter.class)
        );

        Thread.currentThread().interrupt();
        try {
            subject.streamCompletion(
                    runtime("http://localhost/v1beta/openai"),
                    List.of(Message.user("Draw a cat")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    ignored -> {
                    },
                    ignored -> {
                    },
                    ignored -> {
                    },
                    ignored -> {
                    },
                    () -> false,
                    ignored -> {
                    },
                    () -> {
                    }
            );
        } finally {
            Thread.interrupted();
        }

        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("Active-request registration failures cancel Google native requests")
    @SuppressWarnings("unchecked")
    void streamCompletion_whenActiveRequestRegistrationFails_cancelsFuture() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> future = new CompletableFuture<>();
        doReturn(future).when(httpClient).sendAsync(any(), any(HttpResponse.BodyHandler.class));
        var attachmentAuthority = ProviderAttachmentTestSupport.authority();
        var subject = new GoogleAiGenerateContentClient(
                failingFallbackClient(),
                httpClient,
                attachmentAuthority,
                mock(GeneratedImageAttachmentWriter.class)
        );
        var cleared = new AtomicBoolean();

        assertThatThrownBy(() -> subject.streamCompletion(
                runtime("https://generativelanguage.googleapis.com/v1beta/openai"),
                List.of(Message.user("Draw a cat")),
                ReasoningLevel.OFF,
                WebSearchRequestOptions.disabled(),
                ignored -> {
                },
                ignored -> {
                },
                ignored -> {
                },
                () -> false,
                ignored -> {
                    throw new IllegalStateException("registration failed");
                },
                () -> cleared.set(true)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("registration failed");

        assertThat(future).isCancelled();
        assertThat(cleared).isTrue();
    }

    @Test
    @DisplayName("Google native response emits text and generated image parts")
    void streamCompletion_whenGoogleReturnsTextAndInlineImage_emitsTextAndGeneratedImagePart(
            @TempDir Path tempDir
    ) throws Exception {
        byte[] imageBytes = pngBytes();
        String responseBody = """
                {
                  "candidates": [{
                    "content": {
                      "parts": [
                        {"thought": true, "text": "Planning the image."},
                        {"text": "Here it is"},
                        {"inlineData": {"mimeType": "image/png", "data": "%s"}}
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
            Path attachmentRoot = Files.createDirectories(tempDir.resolve("attachments"));
            var attachmentAuthority = new ProviderAttachmentSupport(attachmentRoot);
            var imageWriter = new GeneratedImageAttachmentWriter(attachmentAuthority);
            var subject = new GoogleAiGenerateContentClient(
                    new OpenAiChatCompletionClient(attachmentAuthority),
                    HttpClient.newHttpClient(),
                    attachmentAuthority,
                    imageWriter
            );
            List<String> tokens = new ArrayList<>();
            List<String> thinkingTokens = new ArrayList<>();
            List<GeneratedImagePart> images = new ArrayList<>();

            subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort())),
                    List.of(Message.user("Draw a cat")),
                    ReasoningLevel.HIGH,
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
            assertThat(images).singleElement().satisfies(image -> {
                assertThat(image.width()).isEqualTo(1);
                assertThat(image.height()).isEqualTo(1);
                assertThat(image.altText()).isEqualTo("Generated image");
                Path storedImage = Path.of(image.attachmentRef().storagePath());
                assertThat(storedImage).startsWith(attachmentRoot).isRegularFile();
            });
            assertThat(requestBody.get())
                    .contains("\"responseModalities\":[\"TEXT\",\"IMAGE\"]")
                    .contains("\"thinkingConfig\":{\"includeThoughts\":true}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Generated images are discarded when the caller has no part receiver")
    void streamCompletion_whenNoPartCallbackExists_discardsGeneratedImage(@TempDir Path tempDir) throws Exception {
        String responseBody = """
                {
                  "candidates": [{
                    "content": {"parts": [
                      {"inlineData": {"mimeType": "image/png", "data": "%s"}}
                    ]}
                  }]
                }
                """.formatted(Base64.getEncoder().encodeToString(pngBytes()));
        HttpServer server = responseServer(responseBody, 200);
        Path attachmentRoot = Files.createDirectories(tempDir.resolve("attachments"));
        var attachmentAuthority = new ProviderAttachmentSupport(attachmentRoot);
        var subject = new GoogleAiGenerateContentClient(
                new OpenAiChatCompletionClient(attachmentAuthority),
                HttpClient.newHttpClient(),
                attachmentAuthority,
                new GeneratedImageAttachmentWriter(attachmentAuthority)
        );
        try {
            subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort())),
                    List.of(Message.user("Draw a cat")),
                    ReasoningLevel.OFF,
                    ignored -> {
                    },
                    ignored -> {
                    },
                    () -> false,
                    stream -> {
                    },
                    () -> {
                    }
            );

            try (var paths = Files.walk(attachmentRoot)) {
                assertThat(paths.filter(Files::isRegularFile)).isEmpty();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Generated image ownership transfers before an application callback can fail")
    void emitValidatedParts_whenCallbackStoresThenThrows_keepsDeliveredImage(@TempDir Path tempDir) throws Exception {
        Path attachmentRoot = Files.createDirectories(tempDir.resolve("attachments"));
        var attachmentAuthority = new ProviderAttachmentSupport(attachmentRoot);
        var imageWriter = new GeneratedImageAttachmentWriter(attachmentAuthority);
        var subject = new GoogleAiGenerateContentClient(
                new OpenAiChatCompletionClient(attachmentAuthority),
                HttpClient.newHttpClient(),
                attachmentAuthority,
                imageWriter
        );
        AttachmentRef ref = imageWriter.write(pngBytes(), "image/png");
        var part = new GeneratedImagePart(ref, 1, 1, "Generated image");
        Class<?> emissionType = Class.forName("%s$GoogleAiEmission".formatted(
                GoogleAiGenerateContentClient.class.getName()
        ));
        Method partFactory = emissionType.getDeclaredMethod("part", ContentPart.class);
        partFactory.setAccessible(true);
        Object emission = partFactory.invoke(null, part);
        Method emit = GoogleAiGenerateContentClient.class.getDeclaredMethod(
                "emitValidatedParts",
                List.class,
                Consumer.class,
                Consumer.class,
                Consumer.class,
                BooleanSupplier.class
        );
        emit.setAccessible(true);
        AtomicReference<ContentPart> delivered = new AtomicReference<>();

        assertThatThrownBy(() -> emit.invoke(
                subject,
                List.of(emission),
                (Consumer<String>) ignored -> {
                },
                (Consumer<String>) ignored -> {
                },
                (Consumer<ContentPart>) value -> {
                    delivered.set(value);
                    throw new IllegalStateException("callback failed");
                },
                (BooleanSupplier) () -> false
        )).hasRootCauseMessage("callback failed");

        assertThat(delivered).hasValue(part);
        assertThat(Path.of(ref.storagePath())).startsWith(attachmentRoot).isRegularFile();
    }

    @Test
    @DisplayName("Unsupported inline response data emits a fixed warning in part order")
    void streamCompletion_whenGoogleReturnsUnsupportedInlineData_emitsOrderedFixedWarning() throws Exception {
        String responseBody = """
                {
                  "candidates": [{
                    "content": {"parts": [
                      {"text": "before"},
                      {"inlineData": {"mimeType": "audio/wav", "data": "private-payload"}},
                      {"text": "after"}
                    ]}
                  }]
                }
                """;
        HttpServer server = responseServer(responseBody, 200);
        try {
            var attachmentAuthority = ProviderAttachmentTestSupport.authority();
            var subject = new GoogleAiGenerateContentClient(
                    new OpenAiChatCompletionClient(attachmentAuthority),
                    HttpClient.newHttpClient(),
                    attachmentAuthority,
                    mock(GeneratedImageAttachmentWriter.class)
            );
            List<String> tokens = new ArrayList<>();

            subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort())),
                    List.of(Message.user("Generate mixed output")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    tokens::add,
                    ignored -> {
                    },
                    ignored -> {
                    },
                    () -> false,
                    ignored -> {
                    },
                    () -> {
                    }
            );

            assertThat(tokens).containsExactly(
                    "before",
                    "[Google AI returned unsupported inline data.]",
                    "after"
            ).noneMatch(token -> token.contains("audio/wav") || token.contains("private-payload"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Malformed and unrecognized Google errors never return raw response bodies")
    void errorMessage_whenBodyIsMalformedOrUnrecognized_returnsFixedDiagnostic() throws Exception {
        var attachmentAuthority = ProviderAttachmentTestSupport.authority();
        var subject = new GoogleAiGenerateContentClient(
                new OpenAiChatCompletionClient(attachmentAuthority),
                HttpClient.newHttpClient(),
                attachmentAuthority,
                mock(GeneratedImageAttachmentWriter.class)
        );
        Method method = GoogleAiGenerateContentClient.class.getDeclaredMethod("errorMessage", String.class);
        method.setAccessible(true);

        assertThat(method.invoke(subject, "not-json private-inline-data"))
                .isEqualTo("unparseable error response");
        assertThat(method.invoke(subject, "{\"inlineData\":{\"data\":\"private-inline-data\"}}"))
                .isEqualTo("unrecognized error response");
        assertThat(method.invoke(subject, "{\"error\":{\"message\":\"safe message\\u0000\"}}"))
                .isEqualTo("safe message");
    }

    @Test
    @DisplayName("Generated image MIME declarations must be strict canonical values")
    void canonicalGeneratedImageMime_whenCaseOrWhitespaceDiffers_rejectsDeclaration() throws Exception {
        var attachmentAuthority = ProviderAttachmentTestSupport.authority();
        var subject = new GoogleAiGenerateContentClient(
                new OpenAiChatCompletionClient(attachmentAuthority),
                HttpClient.newHttpClient(),
                attachmentAuthority,
                mock(GeneratedImageAttachmentWriter.class)
        );
        Method method = GoogleAiGenerateContentClient.class.getDeclaredMethod(
                "canonicalGeneratedImageMime",
                String.class
        );
        method.setAccessible(true);

        assertThat(method.invoke(subject, "image/png")).isEqualTo(Optional.of("image/png"));
        assertThat((Optional<?>) method.invoke(subject, " IMAGE/PNG ")).isEmpty();
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
            var subject = new GoogleAiGenerateContentClient(new OpenAiChatCompletionClient(ProviderAttachmentTestSupport.authority()), HttpClient.newHttpClient(), ProviderAttachmentTestSupport.authority(), mock(GeneratedImageAttachmentWriter.class));
            List<String> tokens = new ArrayList<>();
            List<CitationRef> citations = new ArrayList<>();

            subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort()), "gemini-2.5-flash"),
                    List.of(Message.user("Search Google")),
                    ReasoningLevel.HIGH,
                    new WebSearchRequestOptions(true),
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
            assertThat(requestBody.get())
                    .contains("\"google_search\":{}")
                    .contains("\"thinkingConfig\":{\"includeThoughts\":true}")
                    .doesNotContain("responseModalities");
        } finally {
            server.stop(0);
        }
    }



    @Test
    @DisplayName("Google latest aliases reject Web Search before transport starts")
    void streamCompletion_whenGoogleModelUsesLatestAlias_failsBeforeTransport() {
        var subject = new GoogleAiGenerateContentClient(
                failingFallbackClient(),
                HttpClient.newHttpClient(),
                ProviderAttachmentTestSupport.authority(),
                mock(GeneratedImageAttachmentWriter.class)
        );

        assertThatThrownBy(() -> subject.streamCompletion(
                runtime(
                        "https://generativelanguage.googleapis.com/v1beta/openai",
                        "gemini-2.5-flash-latest"
                ),
                List.of(Message.user("Search Google")),
                ReasoningLevel.OFF,
                new WebSearchRequestOptions(true),
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
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model or endpoint");
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
            var subject = new GoogleAiGenerateContentClient(failingFallbackClient(), HttpClient.newHttpClient(), ProviderAttachmentTestSupport.authority(), mock(GeneratedImageAttachmentWriter.class));

            assertThatThrownBy(() -> subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort()), "gemini-2.5-flash"),
                    List.of(Message.user("Search Google")),
                    ReasoningLevel.OFF,
                    new WebSearchRequestOptions(true),
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
            var subject = new GoogleAiGenerateContentClient(failingFallbackClient(), HttpClient.newHttpClient(), ProviderAttachmentTestSupport.authority(), mock(GeneratedImageAttachmentWriter.class));
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

    @Test
    @DisplayName("Google native responses reject partial content with a failure finish reason")
    void streamCompletion_whenCandidateEndsForSafety_rejectsPartialOutput() throws Exception {
        String responseBody = """
                {
                  "candidates": [{
                    "finishReason": "SAFETY",
                    "finishMessage": "Blocked by safety policy",
                    "content": {"parts": [{"text": "partial answer"}]}
                  }]
                }
                """;
        HttpServer server = responseServer(responseBody, 200);
        try {
            var attachmentAuthority = ProviderAttachmentTestSupport.authority();
            var subject = new GoogleAiGenerateContentClient(
                    failingFallbackClient(),
                    HttpClient.newHttpClient(),
                    attachmentAuthority,
                    mock(GeneratedImageAttachmentWriter.class)
            );
            List<String> tokens = new ArrayList<>();

            assertThatThrownBy(() -> subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort())),
                    List.of(Message.user("Draw a cat")),
                    ReasoningLevel.OFF,
                    WebSearchRequestOptions.disabled(),
                    tokens::add,
                    ignored -> {
                    },
                    ignored -> {
                    },
                    () -> false,
                    ignored -> {
                    },
                    () -> {
                    }
            )).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("finishReason=SAFETY");
            assertThat(tokens).isEmpty();
        } finally {
            server.stop(0);
        }
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

    private static HttpServer responseServer(String responseBody, int statusCode) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1beta/models/gemini-3-pro-image-preview:generateContent", exchange -> {
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static byte[] pngBytes() throws Exception {
        var image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
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
        return new ProviderRuntime(
                descriptor,
                "GEMINI_API_KEY",
                baseUrl,
                "test-key",
                model,
                List.of(),
                baseUrl
        );
    }
}
