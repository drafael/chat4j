package com.github.drafael.chat4j.chat.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.apache.commons.lang3.Validate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AnthropicAgentApi {

    private AnthropicAgentApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(List<ContentBlock> content) {
    }

    // Signed thinking blocks must be replayed without dropping fields or changing their values.
    record ContentBlock(Map<String, Object> fields) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        ContentBlock {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        String type() {
            return textField("type");
        }

        String text() {
            return textField("text");
        }

        String thinking() {
            return textField("thinking");
        }

        String id() {
            return textField("id");
        }

        String name() {
            return textField("name");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> input() {
            Object input = fields.get("input");
            Validate.isTrue(input == null || input instanceof Map<?, ?>, "Anthropic tool input must be an object");
            return (Map<String, Object>) input;
        }

        private String textField(String name) {
            return fields.get(name) instanceof String value ? value : null;
        }
    }
}
