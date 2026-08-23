package com.github.drafael.chat4j.tts.provider.speechify;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

final class SpeechifyApi {

    private SpeechifyApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelsResponse(List<Model> models) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Model(String id, String name, String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VoicesResponse(List<Voice> voices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Voice(
            String id,
            @JsonProperty("display_name") String displayName,
            String locale,
            String gender,
            String type
    ) {
    }

    record SynthesisRequest(
            String input,
            @JsonProperty("voice_id") String voiceId,
            @JsonProperty("audio_format") String audioFormat,
            String model
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SynthesisResponse(
            @JsonProperty("audio_data") String audioData,
            @JsonProperty("audio_format") String audioFormat
    ) {
    }
}
