package com.github.drafael.chat4j.prompts;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import java.util.function.BooleanSupplier;

public record CommandCenterAction(String title, Runnable action, BooleanSupplier visible, String keyBinding) {

    public CommandCenterAction(String title, @NonNull Runnable action) {
        this(title, action, () -> true, null);
    }

    public CommandCenterAction(String title, @NonNull Runnable action, @NonNull BooleanSupplier visible) {
        this(title, action, visible, null);
    }

    public CommandCenterAction(
            String title,
            @NonNull Runnable action,
            @NonNull BooleanSupplier visible,
            String keyBinding
    ) {
        Validate.notBlank(title, "title should not be blank");
        this.title = StringUtils.trimToEmpty(title);
        this.action = action;
        this.visible = visible;
        this.keyBinding = StringUtils.trimToNull(keyBinding);
    }

    public boolean isVisible() {
        return visible.getAsBoolean();
    }
}
