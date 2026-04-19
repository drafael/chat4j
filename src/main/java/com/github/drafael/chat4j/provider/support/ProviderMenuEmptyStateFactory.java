package com.github.drafael.chat4j.provider.support;

import javax.swing.JMenuItem;

public class ProviderMenuEmptyStateFactory {

    private static final String NO_PROVIDERS_TEXT = "No providers available";
    private static final String NO_MODELS_TEXT = "No models available";

    public JMenuItem noProvidersAvailableItem() {
        return disabledItem(NO_PROVIDERS_TEXT);
    }

    public JMenuItem noModelsAvailableItem() {
        return disabledItem(NO_MODELS_TEXT);
    }

    private JMenuItem disabledItem(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setEnabled(false);
        return item;
    }
}
