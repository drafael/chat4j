package com.github.drafael.chat4j.chat.content;

import org.apache.commons.lang3.StringUtils;

import java.awt.*;
import java.net.URI;
import java.util.Locale;
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
            Desktop.getDesktop().browse(URI.create(link.trim()));
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
            String scheme = StringUtils.defaultString(uri.getScheme()).toLowerCase(Locale.ROOT);
            if (!ALLOWED_EXTERNAL_LINK_SCHEMES.contains(scheme)) {
                return false;
            }

            return "mailto".equals(scheme)
                    ? StringUtils.isNotBlank(uri.getSchemeSpecificPart())
                    : isAllowedHttpUri(uri);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isAllowedHttpLink(String link) {
        if (StringUtils.isBlank(link)) {
            return false;
        }
        try {
            URI uri = URI.create(link.trim());
            String scheme = StringUtils.defaultString(uri.getScheme()).toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme)) && isAllowedHttpUri(uri);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isAllowedHttpUri(URI uri) {
        return StringUtils.isNotBlank(uri.getHost()) && uri.getRawUserInfo() == null;
    }
}
