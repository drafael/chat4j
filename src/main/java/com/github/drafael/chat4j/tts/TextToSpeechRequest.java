package com.github.drafael.chat4j.tts;

import org.apache.commons.lang3.StringUtils;

public record TextToSpeechRequest(String providerId, String modelId, String voiceId, String text, String responseFormat) {

    public TextToSpeechRequest {
        providerId = StringUtils.trimToEmpty(providerId);
        modelId = StringUtils.trimToEmpty(modelId);
        voiceId = StringUtils.trimToEmpty(voiceId);
        text = StringUtils.defaultString(text);
        responseFormat = StringUtils.trimToEmpty(responseFormat);
    }

    @Override
    public String toString() {
        return "TextToSpeechRequest[providerId=%s, modelId=%s, voiceId=%s, text=<masked:%d chars>, responseFormat=%s]"
                .formatted(providerId, modelId, voiceId, text.length(), responseFormat);
    }
}
