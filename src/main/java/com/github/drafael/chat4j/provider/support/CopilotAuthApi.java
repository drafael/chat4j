package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

final class CopilotAuthApi {

    private CopilotAuthApi() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StoredToken(
            String accessToken,
            String refreshToken,
            Long expiresAtEpochMs,
            String enterpriseDomain,
            Long updatedAtEpochMs,
            String source,
            String oauthScopes
    ) {
        @Override
        public String toString() {
            return "StoredToken[accessToken=****, refreshToken=****, expiresAtEpochMs=%s, enterpriseDomain=%s, updatedAtEpochMs=%s, source=%s, oauthScopes=%s]"
                    .formatted(expiresAtEpochMs, enterpriseDomain, updatedAtEpochMs, source, oauthScopes);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DeviceAuthorizationResponse(
            @JsonProperty("device_code") String deviceCode,
            @JsonProperty("user_code") String userCode,
            @JsonProperty("verification_uri") String verificationUri,
            Integer interval,
            @JsonProperty("expires_in") Integer expiresIn
    ) {
        @Override
        public String toString() {
            return "DeviceAuthorizationResponse[deviceCode=****, userCode=****, verificationUri=****, interval=%s, expiresIn=%s]"
                    .formatted(interval, expiresIn);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccessTokenResponse(@JsonProperty("access_token") String accessToken, String error) {
        @Override
        public String toString() {
            return "AccessTokenResponse[accessToken=****, error=%s]".formatted(error);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SessionTokenResponse(String token, @JsonProperty("expires_at") Long expiresAt) {
        @Override
        public String toString() {
            return "SessionTokenResponse[token=****, expiresAt=%s]".formatted(expiresAt);
        }
    }
}
