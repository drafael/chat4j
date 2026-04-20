package com.github.drafael.chat4j;

import org.apache.commons.lang3.Validate;

import java.util.function.Consumer;

public class ModelMenuSelectionApplyCoordinator {

    public String apply(String selectedModelKey, Consumer<String> setLastMenuSelectedModelKey) {
        Validate.notNull(setLastMenuSelectedModelKey, "setLastMenuSelectedModelKey must not be null");

        setLastMenuSelectedModelKey.accept(selectedModelKey);
        return selectedModelKey;
    }
}
