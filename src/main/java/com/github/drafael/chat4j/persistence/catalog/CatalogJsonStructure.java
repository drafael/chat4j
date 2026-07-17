package com.github.drafael.chat4j.persistence.catalog;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import lombok.NonNull;
import org.apache.commons.lang3.Validate;

/** Validates bounded JSON array structure before domain objects are retained. */
public final class CatalogJsonStructure {

    private CatalogJsonStructure() {
    }

    public static boolean isBoundedArray(@NonNull ObjectMapper objectMapper, String json, int maxItems, int maxTokens) {
        Validate.notNull(json, "json should not be null");
        Validate.isTrue(maxItems >= 0, "maxItems should not be negative");
        Validate.isTrue(maxTokens > 0, "maxTokens should be positive");

        try (JsonParser parser = objectMapper.createParser(json)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                return false;
            }
            int depth = 1;
            int items = 0;
            int tokens = 1;
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                tokens++;
                if (tokens > maxTokens) {
                    return false;
                }
                if (token == JsonToken.END_ARRAY && depth == 1) {
                    return parser.nextToken() == null;
                }
                if (depth == 1 && ++items > maxItems) {
                    return false;
                }
                if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
                    depth++;
                } else if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) {
                    depth--;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
