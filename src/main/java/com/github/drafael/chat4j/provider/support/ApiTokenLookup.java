package com.github.drafael.chat4j.provider.support;

import java.util.Arrays;

public final class ApiTokenLookup implements AutoCloseable {

    private final ApiCredentialSource source;
    private final String tokenId;
    private final char[] token;
    private final String errorMessage;

    private ApiTokenLookup(ApiCredentialSource source, String tokenId, char[] token, String errorMessage) {
        this.source = source;
        this.tokenId = tokenId;
        this.token = token == null ? null : Arrays.copyOf(token, token.length);
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static ApiTokenLookup present(String tokenId, char[] token) {
        return new ApiTokenLookup(ApiCredentialSource.SAVED_TOKEN, tokenId, token, "");
    }

    public static ApiTokenLookup missing(String tokenId) {
        return new ApiTokenLookup(ApiCredentialSource.MISSING, tokenId, null, "");
    }

    public static ApiTokenLookup error(String tokenId, String errorMessage) {
        return new ApiTokenLookup(ApiCredentialSource.ERROR, tokenId, null, errorMessage);
    }

    public ApiCredentialSource source() {
        return source;
    }

    public String tokenId() {
        return tokenId;
    }

    public char[] token() {
        return token == null ? null : Arrays.copyOf(token, token.length);
    }

    public String errorMessage() {
        return errorMessage;
    }

    public boolean present() {
        return source == ApiCredentialSource.SAVED_TOKEN && token != null && token.length > 0;
    }

    @Override
    public void close() {
        if (token != null) {
            Arrays.fill(token, '\0');
        }
    }

    @Override
    public String toString() {
        return "ApiTokenLookup[source=%s, tokenId=%s, token=<masked>, errorMessage=%s]".formatted(source, tokenId, errorMessage);
    }
}
