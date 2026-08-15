package com.github.drafael.chat4j.provider.core.error;

import com.github.drafael.chat4j.provider.support.TogetherModelSupport;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;

public final class ProviderExceptionMapper {

    private static final String REDACTED = "[REDACTED]";
    private static final int REMOTE_FIELD_MAX_CODE_POINTS = 512;
    private static final int REMOTE_FIELD_MAX_BYTES = 2_048;

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

    public static ProviderException mapHttpStatus(
            String providerName,
            String baseUrl,
            int statusCode,
            String errorType,
            String errorCode,
            String message,
            String sensitiveValue
    ) {
        String safeType = sanitizeRemoteField(errorType, sensitiveValue);
        String safeCode = sanitizeRemoteField(errorCode, sensitiveValue);
        String safeMessage = sanitizeRemoteField(message, sensitiveValue);
        String diagnostic = httpDiagnostic(providerName, statusCode, safeType, safeCode, safeMessage);
        boolean hostedTogether = TogetherModelSupport.isTogether(providerName)
                && TogetherModelSupport.isHostedEndpoint(baseUrl);

        if (hostedTogether) {
            return switch (statusCode) {
                case 400, 404 -> new InvalidRequestException(diagnostic, null);
                case 401 -> new AuthenticationException(diagnostic, null);
                case 402 -> new ProviderException(diagnostic, null);
                case 403 -> togetherContextOverflow(safeType, safeCode, safeMessage)
                        ? new InvalidRequestException(diagnostic, null)
                        : new AuthenticationException(diagnostic, null);
                case 429 -> new RateLimitException(diagnostic, null);
                case 500, 502, 503, 504, 524, 529 -> new ProviderUnavailableException(diagnostic, null);
                default -> new ProviderException(diagnostic, null);
            };
        }

        return switch (statusCode) {
            case 400, 404 -> new InvalidRequestException(diagnostic, null);
            case 401, 403 -> new AuthenticationException(diagnostic, null);
            case 429 -> new RateLimitException(diagnostic, null);
            case 500, 502, 503, 504 -> new ProviderUnavailableException(diagnostic, null);
            default -> new ProviderException(diagnostic, null);
        };
    }

    public static String sanitizeMessage(Throwable error, String sensitiveValue) {
        String message = error == null ? null : error.getMessage();
        return sanitizeMessage(message, sensitiveValue);
    }

    public static String sanitizeMessage(String message, String sensitiveValue) {
        String safeMessage = message == null ? "Provider request failed" : message;
        return StringUtils.isBlank(sensitiveValue) ? safeMessage : safeMessage.replace(sensitiveValue, REDACTED);
    }

    private static String sanitizeRemoteField(String value, String sensitiveValue) {
        String redacted = sanitizeMessage(StringUtils.defaultString(value), sensitiveValue)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .replaceAll("\\s{2,}", " ");
        return boundUtf8(redacted, REMOTE_FIELD_MAX_CODE_POINTS, REMOTE_FIELD_MAX_BYTES);
    }

    private static String boundUtf8(String value, int maximumCodePoints, int maximumBytes) {
        StringBuilder bounded = new StringBuilder(Math.min(value.length(), maximumCodePoints));
        int bytes = 0;
        int codePoints = 0;
        for (int index = 0; index < value.length() && codePoints < maximumCodePoints;) {
            int codePoint = value.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + characterBytes > maximumBytes) {
                break;
            }
            bounded.append(character);
            bytes += characterBytes;
            codePoints++;
            index += Character.charCount(codePoint);
        }
        return bounded.toString();
    }

    private static String httpDiagnostic(
            String providerName,
            int statusCode,
            String errorType,
            String errorCode,
            String message
    ) {
        String providerLabel = StringUtils.defaultIfBlank(providerName, "Provider");
        String details = Stream.of(errorType, errorCode, message)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(joining(": "));
        return StringUtils.isBlank(details)
                ? "%s request failed with HTTP %d".formatted(providerLabel, statusCode)
                : "%s request failed (HTTP %d): %s".formatted(providerLabel, statusCode, details);
    }

    private static boolean togetherContextOverflow(String errorType, String errorCode, String message) {
        String evidence = "%s %s %s".formatted(errorType, errorCode, message).toLowerCase(Locale.ROOT);
        if (Strings.CI.containsAny(
                evidence,
                "context length",
                "context_length",
                "context window",
                "input token count"
        )) {
            return true;
        }

        boolean inputOrLimit = Strings.CI.containsAny(
                evidence,
                "input token",
                "input_tokens",
                "token limit",
                "token_limit",
                "context limit",
                "context_limit"
        );
        boolean comparison = Strings.CI.containsAny(
                evidence,
                "exceed",
                "maximum",
                "too many",
                "over limit",
                "above limit"
        );
        return inputOrLimit && comparison;
    }
}
