package com.github.drafael.chat4j.chat.agent;

import java.util.function.BooleanSupplier;
import lombok.NonNull;

@FunctionalInterface
public interface McpApprovalHandler {
    McpApprovalDecision requestApproval(@NonNull McpApprovalRequest request, @NonNull BooleanSupplier cancelled);

    static McpApprovalHandler denyAll() {
        return (request, cancelled) -> McpApprovalDecision.DENY;
    }
}
