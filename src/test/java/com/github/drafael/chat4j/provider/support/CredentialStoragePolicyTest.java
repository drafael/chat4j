package com.github.drafael.chat4j.provider.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.drafael.chat4j.provider.support.CredentialStoragePolicy.MAX_UTF8_BYTES;
import static org.assertj.core.api.Assertions.assertThat;

class CredentialStoragePolicyTest {

    @Test
    @DisplayName("Strict UTF-8 byte boundaries accept exactly 64 KiB")
    void validate_whenValueFitsUtf8Limit_returnsValid() {
        assertThat(CredentialStoragePolicy.validate("a".repeat(MAX_UTF8_BYTES)))
                .isEqualTo(CredentialStoragePolicy.Validation.VALID);
        assertThat(CredentialStoragePolicy.validate("é".repeat(32 * 1024)))
                .isEqualTo(CredentialStoragePolicy.Validation.VALID);
        assertThat(CredentialStoragePolicy.validate("€".repeat(21_845) + "a"))
                .isEqualTo(CredentialStoragePolicy.Validation.VALID);
        assertThat(CredentialStoragePolicy.validate("😀".repeat(16 * 1024)))
                .isEqualTo(CredentialStoragePolicy.Validation.VALID);
    }

    @Test
    @DisplayName("Strict UTF-8 byte boundaries reject values above 64 KiB")
    void validate_whenValueExceedsUtf8Limit_returnsTooLarge() {
        assertThat(CredentialStoragePolicy.validate("a".repeat(MAX_UTF8_BYTES + 1)))
                .isEqualTo(CredentialStoragePolicy.Validation.TOO_LARGE);
        assertThat(CredentialStoragePolicy.validate("é".repeat(32 * 1024) + "a"))
                .isEqualTo(CredentialStoragePolicy.Validation.TOO_LARGE);
        assertThat(CredentialStoragePolicy.validate("€".repeat(21_845) + "aa"))
                .isEqualTo(CredentialStoragePolicy.Validation.TOO_LARGE);
        assertThat(CredentialStoragePolicy.validate("😀".repeat(16 * 1024) + "a"))
                .isEqualTo(CredentialStoragePolicy.Validation.TOO_LARGE);
    }

    @Test
    @DisplayName("Unpaired UTF-16 surrogates are rejected")
    void validate_whenUtf16IsMalformed_returnsMalformed() {
        assertThat(CredentialStoragePolicy.validate("\ud800"))
                .isEqualTo(CredentialStoragePolicy.Validation.MALFORMED_UTF16);
        assertThat(CredentialStoragePolicy.validate("\udc00"))
                .isEqualTo(CredentialStoragePolicy.Validation.MALFORMED_UTF16);
        assertThat(CredentialStoragePolicy.validate("\ud800x"))
                .isEqualTo(CredentialStoragePolicy.Validation.MALFORMED_UTF16);
        assertThat(CredentialStoragePolicy.validate("a".repeat(MAX_UTF8_BYTES + 1) + "\ud800"))
                .isEqualTo(CredentialStoragePolicy.Validation.MALFORMED_UTF16);
    }
}
