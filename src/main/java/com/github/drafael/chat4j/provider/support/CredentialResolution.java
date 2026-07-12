package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;

public record CredentialResolution(ApiCredentialSource source, String credentialId, String value, String errorMessage) {

    public CredentialResolution(ApiCredentialSource source, String credentialId, String value) {
        this(source, credentialId, value, "");
    }

    public static CredentialResolution of(ApiCredentialSource source, String credentialId, String value) {
        return new CredentialResolution(source, credentialId, value, "");
    }

    public static CredentialResolution missing() {
        return new CredentialResolution(ApiCredentialSource.MISSING, null, "", "");
    }

    public static CredentialResolution error(String credentialId, String errorMessage) {
        return new CredentialResolution(ApiCredentialSource.ERROR, credentialId, "", errorMessage);
    }

    public boolean hasValue() {
        return StringUtils.isNotBlank(value);
    }

    @Override
    public String toString() {
        return "CredentialResolution[source=%s, credentialId=%s, value=<masked>, errorMessage=%s]"
                .formatted(source, credentialId, errorMessage);
    }
}
