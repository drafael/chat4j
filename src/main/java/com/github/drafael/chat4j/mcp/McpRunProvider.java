package com.github.drafael.chat4j.mcp;

import java.util.function.BooleanSupplier;
import lombok.NonNull;

@FunctionalInterface
public interface McpRunProvider {
    McpRunSession openRun(@NonNull BooleanSupplier cancelled);

    static McpRunProvider disabled() {
        return ignored -> McpRunSession.empty();
    }
}
