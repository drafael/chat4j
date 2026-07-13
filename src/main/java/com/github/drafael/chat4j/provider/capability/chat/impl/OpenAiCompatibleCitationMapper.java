package com.github.drafael.chat4j.provider.capability.chat.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.github.drafael.chat4j.provider.api.content.CitationRef;
import com.openai.core.JsonValue;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;

final class OpenAiCompatibleCitationMapper {

    private static final TypeReference<Object> OBJECT_TYPE = new TypeReference<>() {
    };

    private OpenAiCompatibleCitationMapper() {
    }

    static List<CitationRef> fromResponseAnnotation(JsonValue annotation) {
        return fromKnownCitationObject(toObject(annotation));
    }

    static List<CitationRef> fromAdditionalProperties(Map<String, JsonValue> properties) {
        if (properties == null || properties.isEmpty()) {
            return emptyList();
        }

        List<CitationRef> citations = new ArrayList<>();
        collectKnownProperties(toObjectMap(properties), citations);
        return List.copyOf(citations);
    }

    private static Map<String, Object> toObjectMap(Map<String, JsonValue> properties) {
        Map<String, Object> objectMap = new LinkedHashMap<>();
        properties.forEach((key, value) -> objectMap.put(key, toObject(value)));
        return objectMap;
    }

    private static Object toObject(JsonValue value) {
        if (value == null) {
            return null;
        }
        try {
            return value.convert(OBJECT_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    private static List<CitationRef> fromKnownCitationObject(Object value) {
        List<CitationRef> citations = new ArrayList<>();
        collectKnownCitationValue(value, citations);
        return List.copyOf(citations);
    }

    @SuppressWarnings("unchecked")
    private static void collectKnownCitationValue(Object value, List<CitationRef> citations) {
        if (value instanceof List<?> list) {
            list.forEach(item -> collectKnownCitationValue(item, citations));
            return;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            return;
        }

        Map<String, Object> map = (Map<String, Object>) rawMap;
        if (isUrlCitation(map)) {
            addUrlCitation(map, citations);
            return;
        }

        collectKnownProperties(map, citations);
    }

    private static void collectKnownProperties(Map<String, Object> map, List<CitationRef> citations) {
        collectUrlCitationValue(map.get("url_citation"), citations);
        collectUrlCitationValue(map.get("urlCitation"), citations);
        collectKnownCitationValue(map.get("annotations"), citations);
        collectCitationListValue(map.get("citations"), citations);
        collectCitationListValue(map.get("url_citations"), citations);
        collectCitationListValue(map.get("urlCitations"), citations);
        collectKnownCitationValue(map.get("message"), citations);
        collectKnownCitationValue(map.get("delta"), citations);
        collectKnownCitationValue(map.get("output"), citations);
        collectKnownCitationValue(map.get("content"), citations);
        collectExecutedTools(map.get("executed_tools"), citations);
        collectExecutedTools(map.get("executedTools"), citations);
        collectSearchResults(map.get("search_results"), citations);
        collectSearchResults(map.get("searchResults"), citations);
    }

    private static boolean isUrlCitation(Map<String, Object> map) {
        return StringUtils.equalsAnyIgnoreCase(stringValue(map.get("type")), "url_citation", "urlCitation")
                && StringUtils.isNotBlank(stringValue(map.get("url")));
    }

    @SuppressWarnings("unchecked")
    private static void collectUrlCitationValue(Object value, List<CitationRef> citations) {
        if (value instanceof List<?> list) {
            list.forEach(item -> collectUrlCitationValue(item, citations));
            return;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            return;
        }

        Map<String, Object> map = (Map<String, Object>) rawMap;
        if (StringUtils.isNotBlank(stringValue(map.get("url")))) {
            addUrlCitation(map, citations);
            return;
        }
        collectKnownProperties(map, citations);
    }

    private static void collectCitationListValue(Object value, List<CitationRef> citations) {
        if (value instanceof List<?> list) {
            list.forEach(item -> collectCitationListValue(item, citations));
            return;
        }
        if (value instanceof String text) {
            UrlCitationMapper.fromUrl("", text, "").ifPresent(citations::add);
            return;
        }
        collectKnownCitationValue(value, citations);
    }

    @SuppressWarnings("unchecked")
    private static void collectExecutedTools(Object value, List<CitationRef> citations) {
        if (value instanceof List<?> list) {
            list.forEach(item -> collectExecutedTools(item, citations));
            return;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            return;
        }

        Map<String, Object> map = (Map<String, Object>) rawMap;
        collectSearchResults(map.get("search_results"), citations);
        collectSearchResults(map.get("searchResults"), citations);
    }

    @SuppressWarnings("unchecked")
    private static void collectSearchResults(Object value, List<CitationRef> citations) {
        if (value instanceof List<?> list) {
            list.forEach(item -> collectSearchResults(item, citations));
            return;
        }
        if (!(value instanceof Map<?, ?> rawMap)) {
            return;
        }

        addUrlCitation((Map<String, Object>) rawMap, citations);
    }

    private static void addUrlCitation(Map<String, Object> map, List<CitationRef> citations) {
        UrlCitationMapper.fromUrl(
                firstString(map, "title", "name"),
                stringValue(map.get("url")),
                firstString(map, "cited_text", "citedText", "snippet", "content", "text")
        ).ifPresent(citations::add);
    }

    private static String firstString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String value = stringValue(map.get(key));
            if (StringUtils.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value instanceof String text ? text : "";
    }
}
