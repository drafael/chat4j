package com.github.drafael.chat4j;


import lombok.NonNull;
import java.util.function.Consumer;

public class ModelMenuSelectionApplyCoordinator {

    public String apply(String selectedModelKey, @NonNull Consumer<String> setLastMenuSelectedModelKey) {
        setLastMenuSelectedModelKey.accept(selectedModelKey);
        return selectedModelKey;
    }
}
