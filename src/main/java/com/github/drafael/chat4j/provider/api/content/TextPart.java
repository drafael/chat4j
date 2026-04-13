package com.github.drafael.chat4j.provider.api.content;

public record TextPart(String text) implements ContentPart {

    public TextPart {
        text = text == null ? "" : text;
    }

    @Override
    public String asTextProjection() {
        return text;
    }
}
