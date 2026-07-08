package com.github.drafael.chat4j.stt.provider.sphinx4;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Sphinx4BundledCatalogLoaderTest {

    @Test
    @DisplayName("Bundled Sphinx4 catalog shows fifteen non-Archive language rows")
    void load_returnsFullNonArchiveCatalog() throws Exception {
        var entries = new Sphinx4BundledCatalogLoader().load();

        assertThat(entries).hasSize(15);
        assertThat(entries).extracting(Sphinx4ModelCatalogEntry::label)
                .contains("US English", "German", "French", "Russian")
                .doesNotContain("Archive");
        assertThat(entries)
                .filteredOn(Sphinx4ModelCatalogEntry::hasVerifiedDownload)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.id()).isEqualTo("cmusphinx-en-us");
                    assertThat(entry.recipe()).isNotNull();
                    assertThat(entry.artifacts()).hasSize(1);
                });
        assertThat(entries)
                .filteredOn(entry -> !entry.hasVerifiedDownload())
                .allSatisfy(entry -> {
                    assertThat(entry.catalogOnly()).isFalse();
                    assertThat(entry.artifacts()).isNotEmpty();
                });
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.canDownload()).isTrue();
            assertThat(entry.sampleRateHz()).isPositive();
            assertThat(entry.recipe()).isNotNull();
            assertThat(entry.recipe().acousticModelPath()).isNotBlank();
            assertThat(entry.recipe().dictionaryPath()).isNotBlank();
            assertThat(entry.recipe().languageModelPath()).isNotBlank();
            assertThat(entry.recipe().requiredFiles()).contains(
                    entry.recipe().dictionaryPath(),
                    entry.recipe().languageModelPath()
            );
        });
        assertThat(entries)
                .filteredOn(entry -> "cmusphinx-pt".equals(entry.id()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.recipe().generateUnigramLanguageModel()).isTrue());
        assertThat(entries)
                .filteredOn(entry -> "cmusphinx-de".equals(entry.id()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.artifacts()).singleElement().extracting(Sphinx4ModelArtifact::archiveFormat).isEqualTo("tar.xz");
                    assertThat(entry.recipe().dictionaryPath()).isEqualTo("etc/voxforge.dic");
                });
    }
}
