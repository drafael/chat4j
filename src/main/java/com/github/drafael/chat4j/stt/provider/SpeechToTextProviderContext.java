package com.github.drafael.chat4j.stt.provider;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import lombok.NonNull;

public record SpeechToTextProviderContext(
        URI baseUri,
        URI transcriptionUri,
        @NonNull CredentialSource credentialSource,
        @NonNull CancellationToken cancellationToken,
        @NonNull Duration timeout,
        LocalSpeechToTextModelReference localModelReference,
        Path tempDirectory
) {

    public SpeechToTextProviderContext(
            URI baseUri,
            URI transcriptionUri,
            @NonNull CredentialSource credentialSource,
            @NonNull CancellationToken cancellationToken,
            @NonNull Duration timeout
    ) {
        this(baseUri, transcriptionUri, credentialSource, cancellationToken, timeout, null, null);
    }

    public SpeechToTextProviderContext(
            URI baseUri,
            URI transcriptionUri,
            @NonNull CredentialSource credentialSource,
            @NonNull CancellationToken cancellationToken,
            @NonNull Duration timeout,
            LocalSpeechToTextModelReference localModelReference
    ) {
        this(baseUri, transcriptionUri, credentialSource, cancellationToken, timeout, localModelReference, null);
    }

    public boolean cancelled() {
        return cancellationToken.cancelled();
    }

    public interface CancellationToken {
        boolean cancelled();

        static CancellationToken never() {
            return () -> false;
        }
    }
}
