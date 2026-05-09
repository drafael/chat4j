package com.github.drafael.chat4j.provider.support;

import lombok.NonNull;
import org.apache.commons.lang3.Validate;

import javax.swing.JRadioButtonMenuItem;
import java.util.function.Consumer;

public class ProviderModelMenuItemFactory {

    private static final int ICON_TEXT_GAP = 8;

    private final ProviderMenuIconResolver providerMenuIconResolver;

    public ProviderModelMenuItemFactory(@NonNull ProviderMenuIconResolver providerMenuIconResolver) {
        this.providerMenuIconResolver = providerMenuIconResolver;
    }

    public CreatedModelItem create(String providerName, String modelId, boolean enabled, @NonNull Consumer<String> onSelected) {
        Validate.notBlank(providerName, "providerName must not be blank");
        Validate.notBlank(modelId, "modelId must not be blank");

        String modelKey = ModelSelectionCodec.format(providerName, modelId);
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(modelId);
        item.setEnabled(enabled);
        item.setIcon(providerMenuIconResolver.resolveModelIcon(providerName, item, enabled));
        item.setIconTextGap(ICON_TEXT_GAP);
        item.addActionListener(e -> onSelected.accept(modelKey));

        return new CreatedModelItem(modelKey, item);
    }

    public record CreatedModelItem(String modelKey, JRadioButtonMenuItem item) {
    }
}
