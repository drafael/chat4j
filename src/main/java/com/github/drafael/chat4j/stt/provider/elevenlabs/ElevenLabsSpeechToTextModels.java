package com.github.drafael.chat4j.stt.provider.elevenlabs;

import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import java.util.List;

public final class ElevenLabsSpeechToTextModels {

    public static final String DEFAULT_MODEL_ID = "scribe_v2";
    public static final SpeechToTextCatalogItem DEFAULT_MODEL = SpeechToTextCatalogItem.of(DEFAULT_MODEL_ID, "Scribe v2");
    public static final List<SpeechToTextCatalogItem> BUNDLED_MODELS = List.of(DEFAULT_MODEL);

    private ElevenLabsSpeechToTextModels() {
    }
}
