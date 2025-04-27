package com.github.drafael.chat4j.provider.core.error;

public class RateLimitException extends ProviderException {

    public RateLimitException(String message, Throwable cause) {
        super(message, cause);
    }
}
