package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.github.drafael.chat4j.provider.api.AuthType;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.api.ProviderDescriptor;
import com.github.drafael.chat4j.provider.api.ReasoningLevel;
import com.github.drafael.chat4j.provider.api.WebSearchRequestOptions;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
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

import static org.assertj.core.api.Assertions.assertThat;
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
            List<GeneratedImagePart> images = new ArrayList<>();

            subject.streamCompletion(
                    runtime("http://localhost:%d/v1beta/openai".formatted(server.getAddress().getPort())),
                    List.of(Message.user("Draw a cat")),
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

            assertThat(tokens).containsExactly("Here it is");
            assertThat(images).containsExactly(new GeneratedImagePart(attachment, null, null, "Generated image"));
            assertThat(requestBody.get()).contains("\"responseModalities\":[\"TEXT\",\"IMAGE\"]");
        } finally {
            server.stop(0);
        }
    }

    private ProviderRuntime runtime(String baseUrl) {
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
        return new ProviderRuntime(descriptor, "GEMINI_API_KEY", baseUrl, "test-key", "gemini-3-pro-image-preview");
    }
}
