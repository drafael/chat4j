package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class MistralConversationsApi {

    private MistralConversationsApi() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Request(
            String model,
            boolean stream,
            boolean store,
            List<Input> inputs,
            String instructions,
            List<Tool> tools,
            @JsonProperty("completion_args") CompletionArgs completionArgs
    ) {
    }

    record Input(String role, String content) {
    }

    record Tool(String type) {
    }

    record CompletionArgs(@JsonProperty("reasoning_effort") String reasoningEffort) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Event(String type, Content content, String code, String message) {
    }

    @JsonDeserialize(using = ContentDeserializer.class)
    sealed interface Content permits TextValue, Chunks, Text, ToolReference, Thinking, Unknown {
    }

    record TextValue(String value) implements Content {
    }

    record Chunks(List<Content> values) implements Content {
    }

    record Text(String value) implements Content {
    }

    record ToolReference(String title, String url, String description) implements Content {
    }

    record Thinking(Content value) implements Content {
    }

    record Unknown(String type) implements Content {
    }

    static final class ContentDeserializer extends JsonDeserializer<Content> {
        @Override
        public Content deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            JsonToken token = parser.currentToken();
            if (token == JsonToken.VALUE_STRING) {
                return new TextValue(parser.getValueAsString());
            }
            if (token == JsonToken.START_ARRAY) {
                List<Content> values = new ArrayList<>();
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    values.add(deserialize(parser, context));
                }
                return new Chunks(List.copyOf(values));
            }
            if (token != JsonToken.START_OBJECT) {
                parser.skipChildren();
                return new Unknown("");
            }

            String type = "";
            String text = "";
            String title = "";
            String url = "";
            String description = "";
            Content thinking = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                parser.nextToken();
                switch (field) {
                    case "type" -> type = parser.getValueAsString("");
                    case "text" -> text = parser.getValueAsString("");
                    case "title" -> title = parser.getValueAsString("");
                    case "url" -> url = parser.getValueAsString("");
                    case "description" -> description = parser.getValueAsString("");
                    case "thinking" -> thinking = deserialize(parser, context);
                    default -> parser.skipChildren();
                }
            }
            return switch (type) {
                case "text" -> new Text(text);
                case "tool_reference" -> new ToolReference(title, url, description);
                case "thinking" -> new Thinking(thinking);
                default -> new Unknown(type);
            };
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorResponse(String message) {
    }
}
