package com.github.drafael.chat4j.chat.content;

import org.apache.commons.lang3.StringUtils;

import java.awt.*;
import java.net.URI;
import java.util.Set;

public final class ExternalLinkSupport {

    private static final Set<String> ALLOWED_EXTERNAL_LINK_SCHEMES = Set.of("http", "https", "mailto");

    private ExternalLinkSupport() {
    }

    public static void openExternalLink(String link) {
        if (!isAllowedExternalLink(link)) {
            return;
        }

        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            return;
        }

        try {
            Desktop.getDesktop().browse(URI.create(link));
        } catch (Exception ignored) {
            // Ignore link open failures to keep chat interaction uninterrupted.
        }
    }

    public static boolean isAllowedExternalLink(String link) {
        if (StringUtils.isBlank(link)) {
            return false;
        }

        try {
            URI uri = URI.create(link.trim());
            String scheme = StringUtils.defaultString(uri.getScheme()).toLowerCase();
            if (!ALLOWED_EXTERNAL_LINK_SCHEMES.contains(scheme)) {
                return false;
            }

            if ("mailto".equals(scheme)) {
                return StringUtils.isNotBlank(uri.getSchemeSpecificPart());
            }

            return StringUtils.isNotBlank(uri.getHost());
        } catch (Exception e) {
            return false;
        }
    }
}
