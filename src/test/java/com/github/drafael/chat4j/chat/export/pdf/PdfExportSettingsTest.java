package com.github.drafael.chat4j.chat.export.pdf;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class PdfExportSettingsTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("PDF export settings default to Auto with conventional publication executable names")
    void settings_whenValuesAreMissing_usesSafeDefaults() {
        var subject = new PdfExportSettings(new SettingsRepository(tempDirectory.resolve("settings.properties")));

        assertThat(subject.mode()).isEqualTo(PdfExportMode.AUTO);
        assertThat(subject.pandocPathOverride()).isEmpty();
        assertThat(subject.pandocExecutable()).isEqualTo("pandoc");
        assertThat(subject.latexEngine()).isEqualTo("lualatex");
        assertThat(subject.latexPathOverride()).isEmpty();
        assertThat(subject.latexExecutable()).isEqualTo("lualatex");
        assertThat(subject.mermaidCliPath()).isEmpty();
        assertThat(subject.chromiumPathOverride()).isEmpty();
    }

    @Test
    @DisplayName("PDF export settings preserve explicit Publication tool paths")
    void settings_whenPublicationValuesPersist_preservesValues() {
        var subject = new PdfExportSettings(new SettingsRepository(tempDirectory.resolve("publication.properties")));

        subject.persistMode(PdfExportMode.PUBLICATION);
        subject.persistPandocPath("/tools/pandoc");
        subject.persistLatexEngine("xelatex");
        subject.persistLatexPath("/tools/xelatex");
        subject.persistMermaidCliPath(" /tools/mmdc ");
        subject.persistChromiumPath(" /tools/chromium ");

        assertThat(subject.mode()).isEqualTo(PdfExportMode.PUBLICATION);
        assertThat(subject.pandocPathOverride()).isEqualTo("/tools/pandoc");
        assertThat(subject.pandocExecutable()).isEqualTo("/tools/pandoc");
        assertThat(subject.latexEngine()).isEqualTo("xelatex");
        assertThat(subject.latexPathOverride()).isEqualTo("/tools/xelatex");
        assertThat(subject.latexExecutable()).isEqualTo("/tools/xelatex");
        assertThat(subject.mermaidCliPath()).isEqualTo("/tools/mmdc");
        assertThat(subject.chromiumPathOverride()).isEqualTo("/tools/chromium");
    }
}
