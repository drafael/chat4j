package com.github.drafael.chat4j.persistence.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogPayloadTest {

    @Test
    @DisplayName("Exactly eight MiB of UTF-8 is accepted without exposing payload content")
    void of_whenPayloadIsExactlyLimit_acceptsPayload() {
        String value = "a".repeat(CatalogPayload.MAX_BYTES);

        CatalogPayload subject = CatalogPayload.of(value);

        assertThat(subject.byteLength()).isEqualTo(CatalogPayload.MAX_BYTES);
        assertThat(subject.toString()).isEqualTo("CatalogPayload[byteLength=8388608]");
        assertThat(subject.bytes()).hasSize(CatalogPayload.MAX_BYTES);
    }

    @Test
    @DisplayName("One byte over eight MiB is rejected")
    void of_whenPayloadExceedsLimit_rejectsPayload() {
        String value = "a".repeat(CatalogPayload.MAX_BYTES + 1);

        assertThatThrownBy(() -> CatalogPayload.of(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("8 MiB");
    }

    @Test
    @DisplayName("Malformed surrogate input is rejected instead of being replaced")
    void of_whenPayloadContainsMalformedSurrogate_rejectsPayload() {
        assertThatThrownBy(() -> CatalogPayload.of("broken-\uD800"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid UTF-8");
    }
}
