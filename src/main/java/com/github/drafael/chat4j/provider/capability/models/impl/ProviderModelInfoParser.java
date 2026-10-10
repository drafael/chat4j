package com.github.drafael.chat4j.provider.capability.models.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.drafael.chat4j.json.JsonCodec;
import com.github.drafael.chat4j.provider.api.ProviderModelInfo;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

final class ProviderModelInfoParser {

    private ProviderModelInfoParser() {
    }

    static Map<String, ProviderModelInfo> parse(String provider, byte[] payload) {
        JsonNode root = JsonCodec.standard().read(payload, JsonNode.class);
        JsonNode models = root.isArray() ? root : root.has("data") ? root.path("data") : root.path("models");
        Map<String, ProviderModelInfo> result = new LinkedHashMap<>();
        if (!models.isArray()) {
            return result;
        }
        StreamSupport.stream(models.spliterator(), false).filter(JsonNode::isObject).forEach(model -> {
            String id = text(model, "id");
            if (StringUtils.isBlank(id)) {
                id = text(model, "name");
            }
            if (StringUtils.isBlank(id)) {
                id = text(model, "key");
            }
            if (StringUtils.isBlank(id)) {
                return;
            }
            if ("Google AI".equals(provider)) {
                id = Strings.CS.removeStart(id, "models/");
            }
            ProviderModelInfo info = info(provider, id, model);
            if (info.hasDetails()) {
                result.putIfAbsent(id, info);
            }
        });
        return Map.copyOf(result);
    }

    private static ProviderModelInfo info(String provider, String id, JsonNode model) {
        var builder = ProviderModelInfo.builder().modelId(id)
                .displayName(text(model, "display_name"))
                .description(text(model, "description"));
        switch (provider) {
            case "OpenRouter" -> builder
                    .displayName(text(model, "name"))
                    .contextWindowTokens(tokens(model.path("context_length")))
                    .maxOutputTokens(tokens(model.path("top_provider").path("max_completion_tokens")))
                    .inputUsdPerMillion(perMillion(model.path("pricing").path("prompt")))
                    .outputUsdPerMillion(perMillion(model.path("pricing").path("completion")));
            case "Together" -> builder
                    .contextWindowTokens(tokens(model.path("context_length")))
                    .inputUsdPerMillion(decimal(model.path("pricing").path("input")))
                    .outputUsdPerMillion(decimal(model.path("pricing").path("output")));
            case "Google AI" -> builder
                    .displayName(text(model, "displayName"))
                    .maxInputTokens(tokens(model.path("inputTokenLimit")))
                    .maxOutputTokens(tokens(model.path("outputTokenLimit")));
            case "GitHub Copilot" -> builder
                    .displayName(text(model, "name"))
                    .contextWindowTokens(tokens(model.path("capabilities").path("limits").path("max_context_window_tokens")))
                    .maxInputTokens(tokens(model.path("capabilities").path("limits").path("max_prompt_tokens")))
                    .maxOutputTokens(tokens(model.path("capabilities").path("limits").path("max_output_tokens")));
            case "Groq" -> builder.contextWindowTokens(tokens(model.path("context_window")));
            case "Mistral" -> builder.displayName(text(model, "name"))
                    .contextWindowTokens(tokens(model.path("max_context_length")));
            case "LM Studio" -> builder.contextWindowTokens(tokens(model.path("max_context_length")));
            default -> { }
        }
        return builder.build();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    private static Long tokens(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() ? value.longValue() : null;
    }

    private static BigDecimal perMillion(JsonNode value) {
        BigDecimal amount = decimal(value);
        return amount == null ? null : amount.multiply(BigDecimal.valueOf(1_000_000));
    }

    private static BigDecimal decimal(JsonNode value) {
        if (!value.isNumber() && !value.isTextual()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
