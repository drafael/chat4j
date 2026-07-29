package com.github.drafael.chat4j.chat.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NonNull;

import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;

public final class AgentToolSchema {

    private AgentToolSchema() {
    }

    public static Map<String, Object> immutableMap(@NonNull Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, immutableValue(value)));
        return unmodifiableMap(result);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> nested.put(String.valueOf(key), immutableValue(item)));
            return unmodifiableMap(nested);
        }
        if (value instanceof List<?> list) {
            List<Object> nested = new ArrayList<>(list.size());
            list.forEach(item -> nested.add(immutableValue(item)));
            return unmodifiableList(nested);
        }
        return value;
    }
}
