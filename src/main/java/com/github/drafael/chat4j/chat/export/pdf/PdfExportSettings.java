package com.github.drafael.chat4j.chat.export.pdf;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

public class PdfExportSettings {

    public static final String MODE_KEY = "chat4j.pdfExport.mode";
    public static final String PANDOC_PATH_KEY = "chat4j.pdfExport.pandocPath";
    public static final String LATEX_ENGINE_KEY = "chat4j.pdfExport.latexEngine";
    public static final String LATEX_PATH_KEY = "chat4j.pdfExport.latexPath";
    public static final String MERMAID_CLI_PATH_KEY = "chat4j.pdfExport.mermaidCliPath";
    public static final String CHROMIUM_PATH_KEY = "chat4j.pdfExport.chromiumPath";
    public static final String DEFAULT_LATEX_ENGINE = "lualatex";

    private final SettingsRepository settingsRepository;

    public PdfExportSettings(@NonNull SettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public PdfExportMode mode() {
        return PdfExportMode.fromSettingValue(settingsRepository.get(MODE_KEY, PdfExportMode.AUTO.settingValue()));
    }

    public void persistMode(@NonNull PdfExportMode mode) {
        settingsRepository.put(MODE_KEY, mode.settingValue());
    }

    public String pandocPathOverride() {
        return StringUtils.trimToEmpty(settingsRepository.get(PANDOC_PATH_KEY, ""));
    }

    public String pandocExecutable() {
        return StringUtils.defaultIfBlank(pandocPathOverride(), "pandoc");
    }

    public void persistPandocPath(String path) {
        settingsRepository.put(PANDOC_PATH_KEY, StringUtils.trimToEmpty(path));
    }

    public String latexEngine() {
        String value = StringUtils.trimToEmpty(settingsRepository.get(LATEX_ENGINE_KEY, DEFAULT_LATEX_ENGINE));
        return "xelatex".equals(value) ? value : DEFAULT_LATEX_ENGINE;
    }

    public void persistLatexEngine(String engine) {
        settingsRepository.put(LATEX_ENGINE_KEY, "xelatex".equals(engine) ? engine : DEFAULT_LATEX_ENGINE);
    }

    public String latexPathOverride() {
        return StringUtils.trimToEmpty(settingsRepository.get(LATEX_PATH_KEY, ""));
    }

    public String latexExecutable() {
        return StringUtils.defaultIfBlank(latexPathOverride(), latexEngine());
    }

    public void persistLatexPath(String path) {
        settingsRepository.put(LATEX_PATH_KEY, StringUtils.trimToEmpty(path));
    }

    public String mermaidCliPath() {
        return StringUtils.trimToEmpty(settingsRepository.get(MERMAID_CLI_PATH_KEY, ""));
    }

    public void persistMermaidCliPath(String path) {
        settingsRepository.put(MERMAID_CLI_PATH_KEY, StringUtils.trimToEmpty(path));
    }

    public String chromiumPathOverride() {
        return StringUtils.trimToEmpty(settingsRepository.get(CHROMIUM_PATH_KEY, ""));
    }

    public void persistChromiumPath(String path) {
        settingsRepository.put(CHROMIUM_PATH_KEY, StringUtils.trimToEmpty(path));
    }
}
