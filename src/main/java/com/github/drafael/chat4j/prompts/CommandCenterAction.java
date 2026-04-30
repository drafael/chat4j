package com.github.drafael.chat4j.prompts;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Validate;

import java.util.function.BooleanSupplier;

public record CommandCenterAction(String title, Runnable action, BooleanSupplier visible) {

    public CommandCenterAction(String title, @NonNull Runnable action) {
        this(title, action, () -> true);
    }

    public CommandCenterAction(String title, @NonNull Runnable action, @NonNull BooleanSupplier visible) {
        Validate.notBlank(title, "title should not be blank");
        this.title = StringUtils.trimToEmpty(title);
        this.action = action;
        this.visible = visible;
    }

    public boolean isVisible() {
        return visible.getAsBoolean();
    }
}
