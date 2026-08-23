package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

final class CodexAuthApi {

    private CodexAuthApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredToken(
            String accessToken,
            String refreshToken,
            Long expiresAtEpochMs,
            Long updatedAtEpochMs,
            String source,
            String oauthScopes
    ) {
        @Override
        public String toString() {
            return "StoredToken[accessToken=****, refreshToken=****, expiresAtEpochMs=%s, updatedAtEpochMs=%s, source=%s, oauthScopes=%s]"
                    .formatted(expiresAtEpochMs, updatedAtEpochMs, source, oauthScopes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(
            @JsonProperty("id_token") String idToken,
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") Object expiresIn
    ) {
        @Override
        public String toString() {
            return "TokenResponse[idToken=****, accessToken=****, refreshToken=****, expiresIn=%s]"
                    .formatted(expiresIn);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record JwtClaims(Object exp) {
    }
}
