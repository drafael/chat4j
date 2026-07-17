package com.github.drafael.chat4j.stt.provider;

public interface SpeechToTextProviderSettings {

    String providerId();

    SpeechToTextCatalogItem selectedModel(SpeechToTextCatalogItem fallback);

    void saveModel(SpeechToTextCatalogItem model);

    String selectedModelId();
}
