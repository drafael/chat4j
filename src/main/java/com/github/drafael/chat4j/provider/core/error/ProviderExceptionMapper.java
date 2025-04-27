package com.github.drafael.chat4j.provider.core.error;

public final class ProviderExceptionMapper {

    private ProviderExceptionMapper() {
    }

    public static Exception map(Exception error) {
        if (error instanceof ProviderException) {
            return error;
        }

        String message = error.getMessage();
        if (message == null) {
            return new ProviderException("Provider request failed", error);
        }

        if (message.contains(" not set")) {
            return new ConfigurationException(message, error);
        }
        if (message.contains("401") || message.contains("403")) {
            return new AuthenticationException(message, error);
        }
        if (message.contains("429")) {
            return new RateLimitException(message, error);
        }
        if (message.contains("400") || message.contains("404")) {
            return new InvalidRequestException(message, error);
        }
        if (message.contains("500") || message.contains("502") || message.contains("503") || message.contains("504")) {
            return new ProviderUnavailableException(message, error);
        }

        return new ProviderException(message, error);
    }
}
