package com.github.drafael.chat4j.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import lombok.NonNull;

public final class JsonCodec {

    private static final JsonCodec STANDARD = new JsonCodec(standardMapper());

    private final ObjectMapper mapper;

    private JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static JsonCodec standard() {
        return STANDARD;
    }

    public <T> T read(@NonNull byte[] json, @NonNull Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new JsonCodecException("JSON could not be decoded.");
        }
    }

    public <T> T read(String json, @NonNull Class<T> type) {
        return read(json == null ? new byte[0] : json.getBytes(StandardCharsets.UTF_8), type);
    }

    public byte[] writeBytes(@NonNull Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new JsonCodecException("JSON could not be encoded.");
        }
    }

    public String writeString(@NonNull Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new JsonCodecException("JSON could not be encoded.");
        }
    }

    public byte[] writePrettyBytes(@NonNull Object value) {
        return writePrettyString(value).getBytes(StandardCharsets.UTF_8);
    }

    public String writePrettyString(@NonNull Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            throw new JsonCodecException("JSON could not be encoded.");
        }
    }

    private static ObjectMapper standardMapper() {
        return new ObjectMapper()
                .disable(JsonParser.Feature.INCLUDE_SOURCE_IN_LOCATION)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public static final class JsonCodecException extends IllegalStateException {
        private JsonCodecException(String message) {
            super(message);
        }
    }
}
