package com.github.drafael.chat4j.tts.provider.groq;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

final class GroqApi {

    private GroqApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelsResponse(List<Model> data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Model(String id) {
    }

    record SynthesisRequest(
            String model,
            String voice,
            String input,
            @JsonProperty("response_format") String responseFormat
    ) {
    }
}
