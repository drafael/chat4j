package com.github.drafael.chat4j.provider.core.error;

import org.apache.commons.lang3.StringUtils;

public final class ProviderExceptionMapper {

    private static final String REDACTED = "[REDACTED]";

    private ProviderExceptionMapper() {
    }

    public static Exception map(Exception error, String sensitiveValue) {
        String originalMessage = error.getMessage();
        String message = sanitizeMessage(originalMessage, sensitiveValue);
        Throwable cause = StringUtils.isBlank(sensitiveValue) ? error : null;

        if (error instanceof ConfigurationException) {
            return cause == error ? error : new ConfigurationException(message, cause);
        }
        if (error instanceof AuthenticationException) {
            return cause == error ? error : new AuthenticationException(message, cause);
        }
        if (error instanceof RateLimitException) {
            return cause == error ? error : new RateLimitException(message, cause);
        }
        if (error instanceof InvalidRequestException) {
            return cause == error ? error : new InvalidRequestException(message, cause);
        }
        if (error instanceof ProviderUnavailableException) {
            return cause == error ? error : new ProviderUnavailableException(message, cause);
        }
        if (error instanceof ProviderException) {
            return cause == error ? error : new ProviderException(message, cause);
        }

        if (message.contains(" not set")) {
            return new ConfigurationException(message, cause);
        }
        if (message.contains("401") || message.contains("403")) {
            return new AuthenticationException(message, cause);
        }
        if (message.contains("429")) {
            return new RateLimitException(message, cause);
        }
        if (message.contains("400") || message.contains("404")) {
            return new InvalidRequestException(message, cause);
        }
        if (message.contains("500") || message.contains("502") || message.contains("503") || message.contains("504")) {
            return new ProviderUnavailableException(message, cause);
        }

        return new ProviderException(message, cause);
    }

    public static String sanitizeMessage(Throwable error, String sensitiveValue) {
        String message = error == null ? null : error.getMessage();
        return sanitizeMessage(message, sensitiveValue);
    }

    public static String sanitizeMessage(String message, String sensitiveValue) {
        String safeMessage = message == null ? "Provider request failed" : message;
        return StringUtils.isBlank(sensitiveValue) ? safeMessage : safeMessage.replace(sensitiveValue, REDACTED);
    }
}
