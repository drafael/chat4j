package com.github.drafael.chat4j.tts.provider.deepgram;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

final class DeepgramApi {

    private DeepgramApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ModelsResponse(List<Model> tts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Model(
            @JsonProperty("canonical_name") String canonicalName,
            String name,
            Metadata metadata
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Metadata(List<String> tags) {
    }

    record SynthesisRequest(String text) {
    }
}
