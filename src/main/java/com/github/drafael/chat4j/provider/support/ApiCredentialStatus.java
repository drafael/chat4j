package com.github.drafael.chat4j.provider.support;

public record ApiCredentialStatus(ApiCredentialSource source, String credentialId, String errorMessage) {
}
