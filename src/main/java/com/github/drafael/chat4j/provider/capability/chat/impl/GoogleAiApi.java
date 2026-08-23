package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

final class GoogleAiApi {

    private GoogleAiApi() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GenerateRequest(
            SystemInstruction systemInstruction,
            List<Content> contents,
            GenerationConfig generationConfig,
            List<Tool> tools
    ) {
    }

    record SystemInstruction(List<Part> parts) {
    }

    record Content(String role, List<Part> parts) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Part(
            String text,
            Boolean thought,
            @JsonProperty("inline_data") @JsonAlias("inlineData") InlineData inlineData
    ) {
        static Part text(String text) {
            return new Part(text, null, null);
        }

        static Part image(String mediaType, String data) {
            return new Part(null, null, new InlineData(mediaType, data));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record InlineData(
            @JsonProperty("mime_type") @JsonAlias("mimeType") String mimeType,
            String data
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GenerationConfig(List<String> responseModalities, ThinkingConfig thinkingConfig) {
    }

    record ThinkingConfig(boolean includeThoughts) {
    }

    record Tool(@JsonProperty("google_search") GoogleSearch googleSearch) {
    }

    record GoogleSearch() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GenerateResponse(
            List<Candidate> candidates,
            PromptFeedback promptFeedback,
            ModelStatus modelStatus,
            UsageMetadata usageMetadata
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Candidate(
            Content content,
            String finishReason,
            String finishMessage,
            GroundingMetadata groundingMetadata
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PromptFeedback(String blockReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelStatus(String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UsageMetadata(
            Integer promptTokenCount,
            Integer candidatesTokenCount,
            Integer thoughtsTokenCount,
            Integer totalTokenCount
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GroundingMetadata(List<GroundingChunk> groundingChunks, List<GroundingSupport> groundingSupports) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GroundingChunk(WebSource web) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WebSource(String uri, String title) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record GroundingSupport(List<Integer> groundingChunkIndices, Segment segment) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Segment(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorEnvelope(ErrorBody error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String message) {
    }
}
