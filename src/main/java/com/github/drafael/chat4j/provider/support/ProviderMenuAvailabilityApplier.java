package com.github.drafael.chat4j.provider.support;

import lombok.NonNull;

import javax.swing.Icon;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import java.util.Map;

public class ProviderMenuAvailabilityApplier {

    private final ProviderAvailabilityLabelFormatter providerAvailabilityLabelFormatter;

    public ProviderMenuAvailabilityApplier() {
        this(new ProviderAvailabilityLabelFormatter());
    }

    ProviderMenuAvailabilityApplier(@NonNull ProviderAvailabilityLabelFormatter providerAvailabilityLabelFormatter) {
        this.providerAvailabilityLabelFormatter = providerAvailabilityLabelFormatter;
    }

    public void apply(
            @NonNull Map<String, JRadioButtonMenuItem> modelMenuItemsByKey,
            @NonNull Map<String, JMenuItem> providerHeaderItemsByName,
            @NonNull Map<String, Boolean> providerEnabledByName,
            @NonNull IconResolver iconResolver
    ) {

        modelMenuItemsByKey.forEach((modelKey, item) -> ModelSelectionCodec.parse(modelKey).ifPresentOrElse(selection -> {
                    Boolean enabled = providerEnabledByName.get(selection.provider());
                    boolean selectable = enabled == null || enabled;
                    item.setEnabled(selectable);
                    item.setIcon(iconResolver.resolveModelIcon(selection.provider(), item, selectable));
                },
                () -> item.setEnabled(true)
        ));

        providerHeaderItemsByName.forEach((providerName, headerItem) -> {
            Boolean enabled = providerEnabledByName.get(providerName);
            boolean providerEnabled = enabled == null || enabled;
            headerItem.setText(providerAvailabilityLabelFormatter.format(providerName, providerEnabled));
            headerItem.setIcon(iconResolver.resolveHeaderIcon(providerName, headerItem, providerEnabled));
        });
    }

    public interface IconResolver {
        Icon resolveModelIcon(String providerName, JRadioButtonMenuItem item, boolean enabled);

        Icon resolveHeaderIcon(String providerName, JMenuItem item, boolean enabled);
    }
}
