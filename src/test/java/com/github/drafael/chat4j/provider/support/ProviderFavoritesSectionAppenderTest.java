package com.github.drafael.chat4j.provider.support;

import com.github.drafael.chat4j.provider.support.ModelSelectionCodec.ModelSelection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JSeparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;

class ProviderFavoritesSectionAppenderTest {

    @Test
    @DisplayName("Append adds favorite model items without a favorites header")
    void append_whenFavoritesExist_addsFavoriteItemsWithoutHeader() {
        var iconResolver = new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), ProviderFavoritesSectionAppenderTest.class);
        var itemFactory = new ProviderModelMenuItemFactory(iconResolver);
        var subject = new ProviderFavoritesSectionAppender(itemFactory);

        JMenu modelsMenu = new JMenu("Model");
        ButtonGroup group = new ButtonGroup();
        Map<String, JRadioButtonMenuItem> menuItemsByKey = new LinkedHashMap<>();
        List<ModelSelection> favorites = List.of(new ModelSelection("OpenAI", "gpt-4.1"));
        Map<String, Boolean> selectableByProvider = Map.of("OpenAI", true);
        AtomicReference<String> selectedModelKey = new AtomicReference<>();

        boolean appended = subject.append(
                modelsMenu,
                group,
                menuItemsByKey,
                favorites,
                selectableByProvider,
                selectedModelKey::set
        );

        assertThat(appended).isTrue();
        assertThat(modelsMenu.getItem(0).getText()).isEqualTo("gpt-4.1");
        assertThat(modelsMenu.getItem(0).isEnabled()).isTrue();
        assertThat(modelsMenu.getMenuComponent(1)).isInstanceOf(JSeparator.class);
        assertThat(menuItemsByKey).containsKey("OpenAI > gpt-4.1");

        menuItemsByKey.get("OpenAI > gpt-4.1").doClick();
        assertThat(selectedModelKey.get()).isEqualTo("OpenAI > gpt-4.1");
    }

    @Test
    @DisplayName("Append returns false and does nothing when favorites are empty")
    void append_whenFavoritesEmpty_returnsFalseAndLeavesMenuUnchanged() {
        var iconResolver = new ProviderMenuIconResolver(new ProviderMenuIconTintResolver(), ProviderFavoritesSectionAppenderTest.class);
        var itemFactory = new ProviderModelMenuItemFactory(iconResolver);
        var subject = new ProviderFavoritesSectionAppender(itemFactory);

        JMenu modelsMenu = new JMenu("Model");
        ButtonGroup group = new ButtonGroup();
        Map<String, JRadioButtonMenuItem> menuItemsByKey = new LinkedHashMap<>();

        boolean appended = subject.append(
                modelsMenu,
                group,
                menuItemsByKey,
                emptyList(),
                Map.of(),
                modelKey -> {
                }
        );

        assertThat(appended).isFalse();
        assertThat(modelsMenu.getItemCount()).isZero();
        assertThat(menuItemsByKey).isEmpty();
    }
}
