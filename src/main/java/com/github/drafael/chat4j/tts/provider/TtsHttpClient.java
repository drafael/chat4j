package com.github.drafael.chat4j.tts.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public final class TtsHttpClient {

    private final TtsHttpTransport transport;
    private final ObjectMapper objectMapper;

    public TtsHttpClient(@NonNull TtsHttpTransport transport) {
        this.transport = transport;
        this.objectMapper = new ObjectMapper();
    }

    TtsHttpResponse get(URI uri, Map<String, String> headers) throws Exception {
        return transport.send(new TtsHttpRequest("GET", uri, headers, null));
    }

    TtsHttpResponse postJson(URI uri, Map<String, String> headers, Object requestBody) throws Exception {
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(requestBody);
        } catch (Exception e) {
            throw new IllegalStateException("TTS JSON request could not be serialized.");
        }
        return transport.send(new TtsHttpRequest("POST", uri, headers, payload));
    }

    <T> T readJson(byte[] body, Class<T> responseType, String invalidResponseMessage) {
        try {
            T response = objectMapper.readValue(body, responseType);
            if (response == null) {
                throw new IllegalStateException(invalidResponseMessage);
            }
            return response;
        } catch (Exception e) {
            throw new IllegalStateException(invalidResponseMessage);
        }
    }

    <T> Optional<T> tryReadJson(byte[] body, Class<T> responseType) {
        try {
            return Optional.ofNullable(objectMapper.readValue(body, responseType));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    String safeErrorDetail(TtsHttpResponse response) {
        String body = new String(response.body(), StandardCharsets.UTF_8);
        if (StringUtils.isBlank(body) || body.stripLeading().startsWith("<")) {
            return "";
        }
        try {
            CommonErrorResponse error = objectMapper.readValue(body, CommonErrorResponse.class);
            return StringUtils.normalizeSpace(error.firstMessage());
        } catch (Exception e) {
            return "";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CommonErrorResponse(
            @JsonDeserialize(using = ObjectMessageDeserializer.class) ErrorDetail error,
            @JsonDeserialize(using = DetailMessageDeserializer.class) ErrorDetail detail,
            String message
    ) {
        private String firstMessage() {
            if (error != null && StringUtils.isNotBlank(error.objectMessage())) {
                return error.objectMessage();
            }
            if (detail != null && StringUtils.isNotBlank(detail.objectMessage())) {
                return detail.objectMessage();
            }
            if (StringUtils.isNotBlank(message)) {
                return message;
            }
            return detail == null ? "" : detail.stringMessage();
        }
    }

    private record ErrorDetail(String objectMessage, String stringMessage) {
    }

    private static final class ObjectMessageDeserializer extends JsonDeserializer<ErrorDetail> {

        @Override
        public ErrorDetail deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            return parser.currentToken() == JsonToken.START_OBJECT
                    ? readObjectMessage(parser)
                    : skipValue(parser);
        }
    }

    private static final class DetailMessageDeserializer extends JsonDeserializer<ErrorDetail> {

        @Override
        public ErrorDetail deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() == JsonToken.START_OBJECT) {
                return readObjectMessage(parser);
            }
            if (parser.currentToken() == JsonToken.VALUE_STRING) {
                return new ErrorDetail(null, parser.getValueAsString(""));
            }
            return skipValue(parser);
        }
    }

    private static ErrorDetail readObjectMessage(JsonParser parser) throws IOException {
        String message = "";
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String fieldName = parser.currentName();
            JsonToken valueToken = parser.nextToken();
            if ("message".equals(fieldName) && valueToken != null && valueToken.isScalarValue()) {
                message = parser.getValueAsString("");
            } else {
                parser.skipChildren();
            }
        }
        return new ErrorDetail(message, null);
    }

    private static ErrorDetail skipValue(JsonParser parser) throws IOException {
        parser.skipChildren();
        return new ErrorDetail(null, null);
    }
}
