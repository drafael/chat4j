package com.github.drafael.chat4j.stt.provider.assemblyai;

import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import java.util.List;

public final class AssemblyAiSpeechToTextModels {

    public static final String DEFAULT_MODEL_ID = "assemblyai-auto";
    public static final String UNIVERSAL_3_5_PRO_MODEL_ID = "universal-3-5-pro";
    public static final String UNIVERSAL_2_MODEL_ID = "universal-2";

    public static final SpeechToTextCatalogItem DEFAULT_MODEL = SpeechToTextCatalogItem.of(
            DEFAULT_MODEL_ID,
            "AssemblyAI Automatic (Universal-3.5 Pro → Universal-2)",
            "Omits speech_models so AssemblyAI uses Universal-3.5 Pro with Universal-2 fallback."
    );
    public static final SpeechToTextCatalogItem UNIVERSAL_3_5_PRO_MODEL = SpeechToTextCatalogItem.of(
            UNIVERSAL_3_5_PRO_MODEL_ID,
            "AssemblyAI Universal-3.5 Pro",
            "Recommended prerecorded model for highest accuracy and speed."
    );
    public static final SpeechToTextCatalogItem UNIVERSAL_2_MODEL = SpeechToTextCatalogItem.of(
            UNIVERSAL_2_MODEL_ID,
            "AssemblyAI Universal-2",
            "Broader-language fallback model for prerecorded transcription."
    );
    public static final List<SpeechToTextCatalogItem> BUNDLED_MODELS = List.of(
            DEFAULT_MODEL,
            UNIVERSAL_3_5_PRO_MODEL,
            UNIVERSAL_2_MODEL
    );

    private AssemblyAiSpeechToTextModels() {
    }
}
