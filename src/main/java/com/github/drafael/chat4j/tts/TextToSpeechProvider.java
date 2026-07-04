package com.github.drafael.chat4j.tts;

import com.github.drafael.chat4j.provider.support.CredentialResolver;
import java.util.List;

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
        return CredentialResolver.hasRequiredCredentials(requiredEnvVar());
    }

    default String apiKey() {
        return CredentialResolver.resolveRequiredApiKey(requiredEnvVar(), null);
    }

    default int maxInputCharacters() {
        return 0;
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
}
