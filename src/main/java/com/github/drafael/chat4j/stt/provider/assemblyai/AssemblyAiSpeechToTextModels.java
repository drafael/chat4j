package com.github.drafael.chat4j.stt.provider.assemblyai;

import com.github.drafael.chat4j.stt.provider.SpeechToTextCatalogItem;
import java.util.List;

public final class AssemblyAiSpeechToTextModels {

    public static final String DEFAULT_MODEL_ID = "assemblyai-auto";
    public static final String STT_3_7_MODEL_ID = "assemblyai-stt-3-7";
    public static final String UNIVERSAL_3_5_PRO_MODEL_ID = "universal-3-5-pro";
    public static final String UNIVERSAL_2_MODEL_ID = "universal-2";

    public static final SpeechToTextCatalogItem DEFAULT_MODEL = SpeechToTextCatalogItem.of(
            DEFAULT_MODEL_ID,
            "AssemblyAI Automatic (provider default)",
            "Omits speech_models so AssemblyAI uses the account's default model."
    );
    public static final SpeechToTextCatalogItem STT_3_7_MODEL = SpeechToTextCatalogItem.of(
            STT_3_7_MODEL_ID,
            "AssemblyAI-STT-3.7",
            "Recommended prerecorded model with 32 languages and native code-switching."
    );
    public static final SpeechToTextCatalogItem UNIVERSAL_3_5_PRO_MODEL = SpeechToTextCatalogItem.of(
            UNIVERSAL_3_5_PRO_MODEL_ID,
            "AssemblyAI Universal-3.5 Pro (deprecated)",
            "Deprecated; remains available until AssemblyAI announces retirement."
    );
    public static final SpeechToTextCatalogItem UNIVERSAL_2_MODEL = SpeechToTextCatalogItem.of(
            UNIVERSAL_2_MODEL_ID,
            "AssemblyAI Universal-2",
            "Broader-language fallback model for prerecorded transcription."
    );
    public static final List<SpeechToTextCatalogItem> BUNDLED_MODELS = List.of(
            DEFAULT_MODEL,
            STT_3_7_MODEL,
            UNIVERSAL_3_5_PRO_MODEL,
            UNIVERSAL_2_MODEL
    );

    private AssemblyAiSpeechToTextModels() {
    }
}
