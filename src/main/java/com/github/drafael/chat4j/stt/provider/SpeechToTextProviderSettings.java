package com.github.drafael.chat4j.stt.provider;

import java.util.function.BooleanSupplier;
import lombok.NonNull;

public interface SpeechToTextProviderSettings {

    String providerId();

    SpeechToTextCatalogItem selectedModel(SpeechToTextCatalogItem fallback);

    void saveModel(SpeechToTextCatalogItem model);

    boolean saveModelIf(@NonNull SpeechToTextCatalogItem model, @NonNull BooleanSupplier condition);

    boolean clearModelIf(@NonNull BooleanSupplier condition);

    String selectedModelId();
}
