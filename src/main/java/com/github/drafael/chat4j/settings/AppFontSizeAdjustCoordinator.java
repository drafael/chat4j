package com.github.drafael.chat4j.settings;

import lombok.NonNull;

public class AppFontSizeAdjustCoordinator {

    private final ResolveAction resolveAction;

    public AppFontSizeAdjustCoordinator(AppFontSizeStepResolver appFontSizeStepResolver) {
        this(appFontSizeStepResolver::resolveAdjustedSize);
    }

    AppFontSizeAdjustCoordinator(@NonNull ResolveAction resolveAction) {
        this.resolveAction = resolveAction;
    }

    public int adjust(
            boolean increase,
            @NonNull SizeOptionsSupplier sizeOptionsSupplier,
            @NonNull CurrentSizeSupplier currentSizeSupplier,
            @NonNull SizeApplier sizeApplier
    ) {

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
