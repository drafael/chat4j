package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSettingsApplyCoordinatorTest {

    @Test
    @DisplayName("Apply resolves runtime settings, applies config, refreshes providers, and marks models menu dirty")
    void apply_whenCalled_runsProviderSettingsFlowInOrder() {
        var calls = new ArrayList<String>();
        var provider = provider("OpenAI");
        var providers = List.of(provider);
        var runtimeConfig = new ProviderRegistry.ProviderRuntimeConfig(true, "https://api.example.invalid");
        var resolvedConfig = Map.of("OpenAI", runtimeConfig);

        var subject = new ProviderSettingsApplyCoordinator(
                configuredProviders -> {
                    calls.add("resolve");
                    assertThat(configuredProviders).isEqualTo(providers);
                    return resolvedConfig;
                },
                runtimeConfigByProvider -> {
                    calls.add("apply-config");
                    assertThat(runtimeConfigByProvider).isEqualTo(resolvedConfig);
                }
        );

        subject.apply(
                providers,
                () -> calls.add("refresh-providers"),
                () -> calls.add("mark-models-dirty")
        );

        assertThat(calls).containsExactly("resolve", "apply-config", "refresh-providers", "mark-models-dirty");
    }

    @Test
    @DisplayName("Apply validates required arguments")
    void apply_whenRequiredArgumentMissing_throwsException() {
        var subject = new ProviderSettingsApplyCoordinator(
                providers -> Map.of(),
                runtimeConfigByProvider -> {
                }
        );

        assertThatThrownBy(() -> subject.apply(null, () -> {
        }, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("providers is marked non-null");

        assertThatThrownBy(() -> subject.apply(List.of(), null, () -> {
        }))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("refreshProviders is marked non-null");

        assertThatThrownBy(() -> subject.apply(List.of(), () -> {
        }, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("markModelsMenuDirty is marked non-null");
    }

    private ProviderRegistry.ProviderDef provider(String name) {
        return new ProviderRegistry.ProviderDef(
                name,
                "API_KEY",
                "https://example.invalid",
                emptyList(),
                ProviderCapabilities.chatAndModels(),
                model -> null,
                () -> emptyList()
        );
    }
}
