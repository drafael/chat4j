package com.github.drafael.chat4j.tts.provider;

public interface TextToSpeechProviderSettings {

    String providerId();

    TextToSpeechCatalogItem selectedModel(TextToSpeechCatalogItem fallback);

    TextToSpeechCatalogItem selectedVoice(TextToSpeechCatalogItem fallback);

    void saveModel(TextToSpeechCatalogItem model);

    void saveVoice(TextToSpeechCatalogItem voice);
}
