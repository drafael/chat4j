package com.github.drafael.chat4j.stt.provider.groq;

import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import java.util.List;

public final class GroqSpeechToTextModels {

    public static final String DEFAULT_MODEL_ID = "whisper-large-v3-turbo";
    public static final SpeechToTextCatalogItem DEFAULT_MODEL = SpeechToTextCatalogItem.of(DEFAULT_MODEL_ID, "Whisper Large v3 Turbo");
    public static final List<SpeechToTextCatalogItem> BUNDLED_MODELS = List.of(
            DEFAULT_MODEL,
            SpeechToTextCatalogItem.of("whisper-large-v3", "Whisper Large v3"),
            SpeechToTextCatalogItem.of("distil-whisper-large-v3-en", "Distil Whisper Large v3 English")
    );

    private GroqSpeechToTextModels() {
    }
}
