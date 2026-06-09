package com.github.drafael.chat4j.provider.api.content;

import org.apache.commons.lang3.ObjectUtils;
import java.util.List;
import java.util.Objects;

import static java.util.stream.Collectors.joining;

public final class ContentParts {

    private ContentParts() {
    }

    public static List<ContentPart> ofText(String text) {
        String safeText = text == null ? "" : text;
        return List.of(new TextPart(safeText));
    }

    public static String toText(List<ContentPart> parts) {
        return ObjectUtils.isEmpty(parts) ? "" : parts.stream()
                .filter(Objects::nonNull)
                .map(ContentPart::asTextProjection)
                .filter(value -> !value.isBlank())
                .collect(joining("\n"));
    }
}
