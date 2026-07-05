package com.github.drafael.chat4j.stt.provider;

import java.net.URI;
import java.time.Duration;
import lombok.NonNull;

public record SpeechToTextProviderContext(
        @NonNull URI baseUri,
        @NonNull URI transcriptionUri,
        @NonNull CredentialSource credentialSource,
        @NonNull CancellationToken cancellationToken,
        @NonNull Duration timeout
) {

    public interface CancellationToken {
        boolean cancelled();
    }
}
