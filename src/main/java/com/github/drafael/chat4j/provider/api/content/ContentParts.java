package com.github.drafael.chat4j.provider.api.content;

import java.util.List;
import java.util.Objects;

public final class ContentParts {

    private ContentParts() {
    }

    public static List<ContentPart> ofText(String text) {
        String safeText = text == null ? "" : text;
        return List.of(new TextPart(safeText));
    }

    public static String toText(List<ContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }

        return parts.stream()
                .filter(Objects::nonNull)
                .map(ContentPart::asTextProjection)
                .filter(value -> !value.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }
}
