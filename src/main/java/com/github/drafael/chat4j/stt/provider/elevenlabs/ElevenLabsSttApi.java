package com.github.drafael.chat4j.stt.provider.elevenlabs;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.stream.Stream;

final class ElevenLabsSttApi {

    private ElevenLabsSttApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelsResponse(List<Model> models) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Model(
            @JsonProperty("model_id") @JsonAlias("id") String id,
            @JsonAlias("label") String name,
            String description,
            @JsonProperty("can_do_speech_to_text") Boolean canDoSpeechToText,
            @JsonProperty("can_do_transcription") Boolean canDoTranscription,
            @JsonProperty("can_do_speech_to_text_batch") Boolean canDoSpeechToTextBatch,
            @JsonProperty("can_do_batch_transcription") Boolean canDoBatchTranscription
    ) {
        boolean hasCapability(boolean value) {
            return Stream.of(canDoSpeechToText, canDoTranscription, canDoSpeechToTextBatch, canDoBatchTranscription)
                    .anyMatch(capability -> capability != null && capability == value);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TranscriptionResponse(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(Object detail, String message, Object error) {
    }
}
