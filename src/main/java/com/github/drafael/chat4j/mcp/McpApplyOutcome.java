package com.github.drafael.chat4j.mcp;

public enum McpApplyOutcome {
    APPLIED,
    REJECTED_OLD_STATE_INTACT,
    REJECTED_ORPHAN_CLEANUP_PENDING,
    APPLIED_CLEANUP_PENDING;

    public boolean applied() {
        return this == APPLIED || this == APPLIED_CLEANUP_PENDING;
    }
}
