package com.github.drafael.chat4j.provider.support;

public enum ApiCredentialSource {
    SAVED_TOKEN,
    PROCESS_ENV,
    SHELL_ENV,
    FALLBACK,
    MISSING,
    ERROR
}
