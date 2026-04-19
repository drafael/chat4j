package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.api.ProviderCapabilities;
import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import com.github.drafael.chat4j.storage.ModelFavoritesService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSeparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderCatalogSectionAppenderTest {

    @Test
    @DisplayName("Append renders provider headers and non-favorite model items")
    void append_whenProvidersHaveModels_addsHeadersAndNonFavoriteItems() throws Exception {
        var favoritesService = ModelFavoritesService.createInMemory();
        favoritesService.setFavorite("OpenAI", "gpt-4.1", true);

        var subject = new ProviderCatalogSectionAppender(
                new ProviderAvailabilityLabelFormatter(),
                new ProviderHeaderMenuItemFactory((providerName, item, enabled) -> null),
                new ProviderFavoritesResolver(favoritesService),
                new ProviderMenuEmptyStateFactory(),
                new ProviderModelMenuItemFactory(
                        new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), ProviderCatalogSectionAppenderTest.class)
                )
        );

        JMenu modelsMenu = new JMenu("Model");
        ButtonGroup group = new ButtonGroup();
        Map<String, JRadioButtonMenuItem> modelMenuItemsByKey = new LinkedHashMap<>();
        Map<String, JMenuItem> providerHeaderItemsByName = new LinkedHashMap<>();
        List<ProviderRegistry.ProviderDef> providers = List.of(provider("OpenAI"), provider("Ollama"));
        Map<String, List<String>> modelsByProvider = Map.of(
                "OpenAI", List.of("gpt-4.1", "gpt-4.1-mini"),
                "Ollama", List.of("llama3.2")
        );
        Map<String, Boolean> providerSelectable = Map.of("OpenAI", true, "Ollama", false);
        AtomicReference<String> selectedModelKey = new AtomicReference<>();

        subject.append(
                modelsMenu,
                group,
                modelMenuItemsByKey,
                providerHeaderItemsByName,
                providers,
                modelsByProvider,
                providerSelectable,
                selectedModelKey::set
        );

        assertThat(providerHeaderItemsByName).containsOnlyKeys("OpenAI", "Ollama");
        assertThat(providerHeaderItemsByName.get("OpenAI").getText()).isEqualTo("OpenAI");
        assertThat(providerHeaderItemsByName.get("Ollama").getText()).isEqualTo("Ollama (offline)");

        assertThat(modelMenuItemsByKey)
                .containsKeys("OpenAI > gpt-4.1-mini", "Ollama > llama3.2")
                .doesNotContainKey("OpenAI > gpt-4.1");
        assertThat(modelMenuItemsByKey.get("Ollama > llama3.2").isEnabled()).isFalse();

        modelMenuItemsByKey.get("OpenAI > gpt-4.1-mini").doClick();
        assertThat(selectedModelKey.get()).isEqualTo("OpenAI > gpt-4.1-mini");

        long separatorCount = java.util.Arrays.stream(modelsMenu.getMenuComponents())
                .filter(component -> component instanceof JSeparator)
                .count();
        assertThat(separatorCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Append adds no-models placeholder when provider has only favorite models")
    void append_whenProviderHasOnlyFavoriteModels_addsNoModelsItem() throws Exception {
        var favoritesService = ModelFavoritesService.createInMemory();
        favoritesService.setFavorite("OpenAI", "gpt-4.1", true);

        var subject = new ProviderCatalogSectionAppender(
                new ProviderAvailabilityLabelFormatter(),
                new ProviderHeaderMenuItemFactory((providerName, item, enabled) -> null),
                new ProviderFavoritesResolver(favoritesService),
                new ProviderMenuEmptyStateFactory(),
                new ProviderModelMenuItemFactory(
                        new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), ProviderCatalogSectionAppenderTest.class)
                )
        );

        JMenu modelsMenu = new JMenu("Model");

        subject.append(
                modelsMenu,
                new ButtonGroup(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                List.of(provider("OpenAI")),
                Map.of("OpenAI", List.of("gpt-4.1")),
                Map.of("OpenAI", true),
                modelKey -> {
                }
        );

        JMenuItem noModelsItem = modelsMenu.getItem(1);
        assertThat(noModelsItem.getText()).isEqualTo("No models available");
        assertThat(noModelsItem.isEnabled()).isFalse();
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
