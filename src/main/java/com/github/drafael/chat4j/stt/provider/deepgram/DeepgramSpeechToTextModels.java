package com.github.drafael.chat4j.stt.provider.deepgram;

import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import java.util.List;

public final class DeepgramSpeechToTextModels {

    public static final String DEFAULT_MODEL_ID = "nova-3";
    public static final SpeechToTextCatalogItem DEFAULT_MODEL = SpeechToTextCatalogItem.of(DEFAULT_MODEL_ID, "Deepgram Nova 3");
    public static final List<SpeechToTextCatalogItem> BUNDLED_MODELS = List.of(
            DEFAULT_MODEL,
            SpeechToTextCatalogItem.of("nova-3-general", "Deepgram Nova 3 General"),
            SpeechToTextCatalogItem.of("nova-2-general", "Deepgram Nova 2 General")
    );

    private DeepgramSpeechToTextModels() {
    }
}
