package com.github.drafael.chat4j.stt.provider.deepgram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

final class DeepgramSttApi {

    private DeepgramSttApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelsResponse(List<Model> stt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Model(boolean batch, @JsonProperty("canonical_name") String canonicalName, String architecture, String version) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TranscriptionResponse(Results results) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Results(List<Channel> channels) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Channel(List<Alternative> alternatives) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Alternative(String transcript) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(
            @JsonProperty("err_msg") String errorMessage,
            @JsonProperty("err_code") String errorCode,
            Object detail,
            String message,
            Object error
    ) {
    }
}
