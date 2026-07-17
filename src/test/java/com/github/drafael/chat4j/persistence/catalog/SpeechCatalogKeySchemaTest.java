package com.github.drafael.chat4j.persistence.catalog;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpeechCatalogKeySchemaTest {

    @Test
    @DisplayName("Distinct provider identifiers cannot share a catalog slug")
    void validateUniqueProviderSlugs_whenIdsCollapseToSameSlug_rejectsConfiguration() {
        assertThatThrownBy(() -> SpeechCatalogKeySchema.validateUniqueProviderSlugs(List.of("provider!", "provider?")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Provider slugs longer than 96 characters are rejected")
    void providerSlug_whenCanonicalSlugIsTooLong_rejectsProvider() {
        assertThatThrownBy(() -> SpeechCatalogKeySchema.providerSlug("a".repeat(97)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Malformed UTF-16 provider identifiers are rejected instead of replaced")
    void providerSlug_whenProviderContainsMalformedSurrogate_rejectsProvider() {
        assertThatThrownBy(() -> SpeechCatalogKeySchema.providerSlug("provider-\uD800"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("valid UTF-8");
    }

    @Test
    @DisplayName("Malformed persisted reference keys are ignored during schema discovery")
    void groupForReferenceKey_whenSlugIsTooLong_returnsEmpty() {
        String key = "chat4j.stt.catalog.%s.modelsFile".formatted("a".repeat(97));

        assertThat(SpeechCatalogKeySchema.groupForReferenceKey(key)).isEmpty();
    }

    @Test
    @DisplayName("Generic STT snapshots cannot use the Vosk raw catalog namespace")
    void sttModels_whenProviderIsVosk_rejectsReservedSlot() {
        assertThatThrownBy(() -> SpeechCatalogKeySchema.sttModels("vosk"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
