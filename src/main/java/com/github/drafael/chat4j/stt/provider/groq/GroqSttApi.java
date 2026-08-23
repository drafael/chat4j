package com.github.drafael.chat4j.stt.provider.groq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

final class GroqSttApi {

    private GroqSttApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelsResponse(List<Model> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Model(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TranscriptionResponse(String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(ErrorValue error, ErrorValue detail, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorValue(String message) {
    }
}
