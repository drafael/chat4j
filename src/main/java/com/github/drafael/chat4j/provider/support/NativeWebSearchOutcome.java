package com.github.drafael.chat4j.provider.support;

public enum NativeWebSearchOutcome {
    PENDING,
    UNSUPPORTED,
    OPTIONAL,
    REQUIRED;

    public boolean supported() {
        return this == OPTIONAL || this == REQUIRED;
    }

    public boolean optional() {
        return this == OPTIONAL;
    }

    public boolean required() {
        return this == REQUIRED;
    }
}
