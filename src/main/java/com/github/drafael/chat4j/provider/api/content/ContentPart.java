package com.github.drafael.chat4j.provider.api.content;

public sealed interface ContentPart permits TextPart, ImagePart, FilePart {

    String asTextProjection();

    default boolean isEmpty() {
        return asTextProjection().isBlank();
    }
}
