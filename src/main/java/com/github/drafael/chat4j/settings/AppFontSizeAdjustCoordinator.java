package com.github.drafael.chat4j.settings;

import org.apache.commons.lang3.Validate;

public class AppFontSizeAdjustCoordinator {

    private final ResolveAction resolveAction;

    public AppFontSizeAdjustCoordinator(AppFontSizeStepResolver appFontSizeStepResolver) {
        this(appFontSizeStepResolver::resolveAdjustedSize);
    }

    AppFontSizeAdjustCoordinator(ResolveAction resolveAction) {
        this.resolveAction = Validate.notNull(resolveAction, "resolveAction must not be null");
    }

    public int adjust(
            boolean increase,
            SizeOptionsSupplier sizeOptionsSupplier,
            CurrentSizeSupplier currentSizeSupplier,
            SizeApplier sizeApplier
    ) {
        Validate.notNull(sizeOptionsSupplier, "sizeOptionsSupplier must not be null");
        Validate.notNull(currentSizeSupplier, "currentSizeSupplier must not be null");
        Validate.notNull(sizeApplier, "sizeApplier must not be null");

        int adjustedSize = resolveAction.resolve(sizeOptionsSupplier.get(), currentSizeSupplier.get(), increase);
        sizeApplier.apply(adjustedSize);
        return adjustedSize;
    }

    @FunctionalInterface
    interface ResolveAction {
        int resolve(int[] sizeOptions, int currentSize, boolean increase);
    }

    @FunctionalInterface
    public interface SizeOptionsSupplier {
        int[] get();
    }

    @FunctionalInterface
    public interface CurrentSizeSupplier {
        int get();
    }

    @FunctionalInterface
    public interface SizeApplier {
        void apply(int adjustedSize);
    }
}
