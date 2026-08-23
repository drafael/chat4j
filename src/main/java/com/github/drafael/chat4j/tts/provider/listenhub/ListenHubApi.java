package com.github.drafael.chat4j.tts.provider.listenhub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.List;

final class ListenHubApi {

    private ListenHubApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Response(
            @JsonDeserialize(using = StrictIntegerDeserializer.class) Integer code,
            String message,
            VoiceData data
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VoiceData(List<Voice> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Voice(String speakerId, String name, Profile profile) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Profile(String description) {
    }

    record SynthesisRequest(
            String input,
            String voice,
            @JsonProperty("response_format") String responseFormat
    ) {
    }

    static final class StrictIntegerDeserializer extends JsonDeserializer<Integer> {

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
                return (Integer) context.handleUnexpectedToken(Integer.class, parser);
            }
            long value = parser.getLongValue();
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                return (Integer) context.handleWeirdNumberValue(Integer.class, value, "value is outside the integer range");
            }
            return (int) value;
        }
    }
}
