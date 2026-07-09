package com.github.drafael.chat4j.stt.provider.whisper;

public record WhisperTranscriptionOptions(
        String modelId,
        boolean englishOnly,
        int threads
) {

    public static WhisperTranscriptionOptions fromModelId(String modelId) {
        int processors = Runtime.getRuntime().availableProcessors();
        return new WhisperTranscriptionOptions(modelId, modelId != null && modelId.contains(".en"), Math.max(1, Math.min(processors, 8)));
    }
}
