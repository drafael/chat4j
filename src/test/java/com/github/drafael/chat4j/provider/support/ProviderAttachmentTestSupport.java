package com.github.drafael.chat4j.provider.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ProviderAttachmentTestSupport {

    private static final ProviderAttachmentSupport AUTHORITY = createAuthority();

    private ProviderAttachmentTestSupport() {
    }

    public static ProviderAttachmentSupport authority() {
        return AUTHORITY;
    }

    private static ProviderAttachmentSupport createAuthority() {
        try {
            Path root = Path.of("target", "test-provider-attachments", "authority");
            Files.createDirectories(root);
            return new ProviderAttachmentSupport(root);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the managed attachment test root.", e);
        }
    }

    public static Path managedRoot(ProviderAttachmentSupport authority) {
        return authority.managedRoot();
    }
}
