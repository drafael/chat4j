package com.github.drafael.chat4j.persistence.settings;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsKeySlugsTest {

    @Test
    @DisplayName("Provider slugs preserve existing normalization behavior")
    void providerSlug_whenProviderNamesVary_returnsCompatibleSlug() {
        assertThat(SettingsKeySlugs.providerSlug(null)).isEqualTo("unknown");
        assertThat(SettingsKeySlugs.providerSlug("   ")).isEqualTo("unknown");
        assertThat(SettingsKeySlugs.providerSlug("!!!")).isEqualTo("unknown");
        assertThat(SettingsKeySlugs.providerSlug("東京")).isEqualTo("unknown");
        assertThat(SettingsKeySlugs.providerSlug("LM Studio")).isEqualTo("lm-studio");
        assertThat(SettingsKeySlugs.providerSlug("OpenAI Codex")).isEqualTo("openai-codex");
        assertThat(SettingsKeySlugs.providerSlug("xAI")).isEqualTo("xai");
    }
}
