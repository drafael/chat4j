package com.github.drafael.chat4j.persistence.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogJsonStructureTest {

    @Test
    @DisplayName("An array exceeding its item limit is rejected while streaming")
    void isBoundedArray_whenArrayHasTooManyItems_returnsFalse() {
        assertThat(CatalogJsonStructure.isBoundedArray("[null,null,null]", 2, 100)).isFalse();
    }

    @Test
    @DisplayName("An object exceeding the token limit is rejected while streaming")
    void isBoundedArray_whenNestedContentHasTooManyTokens_returnsFalse() {
        assertThat(CatalogJsonStructure.isBoundedArray("[{\"a\":1,\"b\":2}]", 1, 5)).isFalse();
    }

    @Test
    @DisplayName("A bounded array with no trailing document is accepted")
    void isBoundedArray_whenDocumentIsWithinLimits_returnsTrue() {
        assertThat(CatalogJsonStructure.isBoundedArray("[{\"a\":1},null]", 2, 20)).isTrue();
    }
}
