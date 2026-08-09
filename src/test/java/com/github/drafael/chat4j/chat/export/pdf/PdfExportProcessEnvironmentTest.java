package com.github.drafael.chat4j.chat.export.pdf;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PdfExportProcessEnvironmentTest {

    private static final Map<String, String> SOURCE = Map.of(
            "PATH", "/tools",
            "LANG", "en_US.UTF-8",
            "TEXMFHOME", "/texmf",
            "PUPPETEER_EXECUTABLE_PATH", "/tools/chrome",
            "OPENAI_API_KEY", "secret",
            "SERVICE_TOKEN", "secret",
            "LC_SECRET", "secret"
    );

    @Test
    @DisplayName("Mermaid subprocesses receive browser operations variables without credentials")
    void forMermaid_whenEnvironmentContainsSecrets_keepsOnlyRequiredValues() {
        assertThat(PdfExportProcessEnvironment.forMermaid(SOURCE))
                .containsEntry("PATH", "/tools")
                .containsEntry("LANG", "en_US.UTF-8")
                .containsEntry("PUPPETEER_EXECUTABLE_PATH", "/tools/chrome")
                .doesNotContainKeys("TEXMFHOME", "OPENAI_API_KEY", "SERVICE_TOKEN", "LC_SECRET");
    }

    @Test
    @DisplayName("Publication subprocesses receive TeX operations variables without credentials")
    void forPublication_whenEnvironmentContainsSecrets_keepsOnlyRequiredValues() {
        assertThat(PdfExportProcessEnvironment.forPublication(SOURCE))
                .containsEntry("PATH", "/tools")
                .containsEntry("LANG", "en_US.UTF-8")
                .containsEntry("TEXMFHOME", "/texmf")
                .doesNotContainKeys(
                        "PUPPETEER_EXECUTABLE_PATH",
                        "OPENAI_API_KEY",
                        "SERVICE_TOKEN",
                        "LC_SECRET"
                );
    }
}
