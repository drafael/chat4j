package com.github.drafael.chat4j.chat.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedUtf8Test {

    @Test
    @DisplayName("Multiline presentation retains line feeds while removing unsafe controls")
    void multilinePresentation_whenTextContainsControls_preservesOnlyLineFeeds() {
        String source = "{\n  \"value\": \"safe\"\u202e\n}\u0000";

        String result = BoundedUtf8.multilinePresentation(source, 1_000, 1_000);

        assertThat(result).isEqualTo("{\n  \"value\": \"safe\"\n}");
    }

    @Test
    @DisplayName("Multiline presentation applies code point bounds across line feeds")
    void multilinePresentation_whenCodePointLimitIsReached_boundsWholeValue() {
        String result = BoundedUtf8.multilinePresentation("ab\ncd", 3, 100);

        assertThat(result).isEqualTo("ab\n");
    }
}
