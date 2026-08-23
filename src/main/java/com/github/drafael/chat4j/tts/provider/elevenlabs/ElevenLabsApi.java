package com.github.drafael.chat4j.tts.provider.elevenlabs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.util.List;

final class ElevenLabsApi {

    private ElevenLabsApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonDeserialize(using = ModelsResponseDeserializer.class)
    record ModelsResponse(List<Model> models) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonDeserialize(using = ModelDeserializer.class)
    record Model(
            String modelId,
            String id,
            String name,
            String label,
            Boolean canDoTextToSpeech,
            boolean textToSpeechCapabilityPresent
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VoicesResponse(List<Voice> voices) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Voice(
            @JsonProperty("voice_id") String voiceId,
            String id,
            String name,
            String label,
            String description,
            String category
    ) {
    }

    record SynthesisRequest(String text, @JsonProperty("model_id") String modelId) {
    }

    static final class ModelDeserializer extends JsonDeserializer<Model> {

        @Override
        public Model deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                return new Model(null, null, null, null, null, false);
            }
            String modelId = null;
            String id = null;
            String name = null;
            String label = null;
            Boolean canDoTextToSpeech = null;
            boolean capabilityPresent = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if (valueToken == null) {
                    continue;
                }
                switch (fieldName) {
                    case "model_id" -> modelId = scalarText(parser, valueToken);
                    case "id" -> id = scalarText(parser, valueToken);
                    case "name" -> name = scalarText(parser, valueToken);
                    case "label" -> label = scalarText(parser, valueToken);
                    case "can_do_text_to_speech" -> {
                        capabilityPresent = true;
                        canDoTextToSpeech = valueToken == JsonToken.VALUE_NULL
                                ? null
                                : parser.getValueAsBoolean(false);
                    }
                    default -> parser.skipChildren();
                }
            }
            return new Model(modelId, id, name, label, canDoTextToSpeech, capabilityPresent);
        }

        private static String scalarText(JsonParser parser, JsonToken token) throws IOException {
            if (!token.isScalarValue() || token == JsonToken.VALUE_NULL) {
                parser.skipChildren();
                return null;
            }
            return parser.getValueAsString("");
        }
    }

    static final class ModelsResponseDeserializer extends JsonDeserializer<ModelsResponse> {

        @Override
        public ModelsResponse deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.START_ARRAY) {
                return new ModelsResponse(List.of(parser.readValueAs(Model[].class)));
            }
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                parser.skipChildren();
                return new ModelsResponse(null);
            }
            List<Model> models = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("models".equals(fieldName) && valueToken == JsonToken.START_ARRAY) {
                    models = List.of(parser.readValueAs(Model[].class));
                } else {
                    parser.skipChildren();
                }
            }
            return new ModelsResponse(models);
        }
    }
}
