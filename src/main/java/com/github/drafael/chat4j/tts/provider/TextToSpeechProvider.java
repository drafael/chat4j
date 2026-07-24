package com.github.drafael.chat4j.tts.provider;

import com.github.drafael.chat4j.tts.audio.TextToSpeechAudio;
import java.util.List;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

public interface TextToSpeechProvider {

    String id();

    String displayName();

    String requiredEnvVar();

    TextToSpeechCatalogItem defaultModel();

    TextToSpeechCatalogItem defaultVoice();

    List<TextToSpeechCatalogItem> bundledModels();

    List<TextToSpeechCatalogItem> bundledVoices();

    default boolean available() {
        return StringUtils.isBlank(requiredEnvVar());
    }

    default String apiKey() {
        throw new IllegalStateException("Credential resolver is unavailable for %s.".formatted(displayName()));
    }

    default int maxInputCharacters() {
        return 0;
    }

    default String defaultResponseFormat() {
        return "mp3";
    }

    default String unavailableLabel() {
        return StringUtils.isBlank(requiredEnvVar())
                ? "%s (unavailable)".formatted(displayName())
                : "%s (requires %s)".formatted(displayName(), requiredEnvVar());
    }

    default String unavailableMessage() {
        return StringUtils.isBlank(requiredEnvVar())
                ? "%s is not available.".formatted(displayName())
                : "%s requires %s.".formatted(displayName(), requiredEnvVar());
    }

    default String availableMessage() {
        return "Using %s.".formatted(displayName());
    }

    default TextToSpeechCatalogItem normalizeModelSelection(TextToSpeechCatalogItem model) {
        return model;
    }

    default TextToSpeechCatalogItem normalizeVoiceSelection(TextToSpeechCatalogItem voice) {
        return voice;
    }

    default List<TextToSpeechCatalogItem> voicesForModel(TextToSpeechCatalogItem model, List<TextToSpeechCatalogItem> voices) {
        return voices == null ? emptyList() : voices;
    }

    List<TextToSpeechCatalogItem> fetchModels() throws Exception;

    List<TextToSpeechCatalogItem> fetchVoices() throws Exception;

    TextToSpeechAudio synthesize(TextToSpeechRequest request) throws Exception;

    default TextToSpeechAudio synthesize(TextToSpeechRequest request, String apiKey) throws Exception {
        if (StringUtils.isNotBlank(requiredEnvVar())) {
            throw new IllegalStateException("Provider does not support request-owned credentials: %s".formatted(displayName()));
        }
        return synthesize(request);
    }
}
