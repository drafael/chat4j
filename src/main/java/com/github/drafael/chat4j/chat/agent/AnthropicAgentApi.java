package com.github.drafael.chat4j.chat.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

final class AnthropicAgentApi {

    private AnthropicAgentApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<ContentBlock> content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ContentBlock(String type, String text, String id, String name, Map<String, Object> input) {
        Map<String, Object> asToolUseMap() {
            return Map.of(
                    "type", type,
                    "id", id == null ? "" : id,
                    "name", name == null ? "" : name,
                    "input", input == null ? Map.of() : input
            );
        }
    }
}
