package com.github.drafael.chat4j.stt.provider.assemblyai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

final class AssemblyAiSttApi {

    private AssemblyAiSttApi() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CreateTranscriptRequest(@JsonProperty("audio_url") String audioUrl, @JsonProperty("speech_models") List<String> speechModels) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record UploadResponse(@JsonProperty("upload_url") String uploadUrl) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreateTranscriptResponse(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TranscriptResponse(String status, String text, Object error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(Object error, String message) {
    }
}
