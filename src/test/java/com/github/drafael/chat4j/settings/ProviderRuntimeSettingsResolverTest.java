package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsKeys;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderRuntimeSettingsResolverTest {

    @Test
    @DisplayName("Resolve uses provider defaults when settings are missing")
    void resolve_whenSettingsMissing_usesProviderDefaults() throws Exception {
        var settingsRepo = settingsRepo("resolver-defaults");
        var subject = new ProviderRuntimeSettingsResolver(settingsRepo);
        var provider = provider("OpenAI", "https://api.openai.com/v1");

        ProviderRegistry.ProviderRuntimeConfig config = subject.resolve(provider);

        assertThat(config.enabled()).isTrue();
        assertThat(config.baseUrl()).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    @DisplayName("Resolve uses configured enabled flag and base URL")
    void resolve_whenSettingsConfigured_usesConfiguredValues() throws Exception {
        var settingsRepo = settingsRepo("resolver-configured");
        settingsRepo.put(SettingsKeys.providerEnabledKey("OpenAI"), "false");
        settingsRepo.put(SettingsKeys.providerBaseUrlKey("OpenAI"), "https://gateway.example/v1");

        var subject = new ProviderRuntimeSettingsResolver(settingsRepo);
        var provider = provider("OpenAI", "https://api.openai.com/v1");

        ProviderRegistry.ProviderRuntimeConfig config = subject.resolve(provider);

        assertThat(config.enabled()).isFalse();
        assertThat(config.baseUrl()).isEqualTo("https://gateway.example/v1");
    }

    @Test
    @DisplayName("Resolve falls back to default URL when configured URL is blank")
    void resolve_whenConfiguredBaseUrlIsBlank_usesProviderDefaultBaseUrl() throws Exception {
        var settingsRepo = settingsRepo("resolver-blank-url");
        settingsRepo.put(SettingsKeys.providerBaseUrlKey("OpenAI"), "   ");

        var subject = new ProviderRuntimeSettingsResolver(settingsRepo);
        var provider = provider("OpenAI", "https://api.openai.com/v1");

        ProviderRegistry.ProviderRuntimeConfig config = subject.resolve(provider);

        assertThat(config.baseUrl()).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    @DisplayName("Resolve all returns config entries keyed by provider name")
    void resolveAll_whenProvidersProvided_returnsConfigMapByProviderName() throws Exception {
        var settingsRepo = settingsRepo("resolver-all");
        settingsRepo.put(SettingsKeys.providerEnabledKey("OpenAI"), "false");

        var subject = new ProviderRuntimeSettingsResolver(settingsRepo);
        var openAi = provider("OpenAI", "https://api.openai.com/v1");
        var anthropic = provider("Anthropic", "https://api.anthropic.com");

        Map<String, ProviderRegistry.ProviderRuntimeConfig> configs = subject.resolveAll(List.of(openAi, anthropic));

        assertThat(configs).hasSize(2);
        assertThat(configs.get("OpenAI").enabled()).isFalse();
        assertThat(configs.get("Anthropic").enabled()).isTrue();
    }

    private SettingsRepository settingsRepo(String dbName) throws SQLException {
        DataSource dataSource = createDataSource(dbName);
        createSettingsTable(dataSource);
        return new SettingsRepository(dataSource);
    }

    private DataSource createDataSource(String dbName) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(dbName));
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private void createSettingsTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "CREATE TABLE IF NOT EXISTS settings (\"key\" VARCHAR(100) PRIMARY KEY, \"value\" VARCHAR(500))"
             )
        ) {
            statement.execute();
        }
    }

    private ProviderRegistry.ProviderDef provider(String name, String baseUrl) {
        return new ProviderRegistry.ProviderDef(
                name,
                "API_KEY",
                baseUrl,
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                model -> null,
                () -> emptyList()
        );
    }
}
