package com.github.drafael.chat4j.provider.support;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

public final class WebSearchSourceUrlNormalizer {

    private WebSearchSourceUrlNormalizer() {
    }

    public static Optional<NormalizedUrl> normalize(String value) {
        if (StringUtils.isBlank(value) || value.chars().anyMatch(Character::isISOControl)) {
            return Optional.empty();
        }
        try {
            String displayUrl = value.trim();
            URI uri = URI.create(displayUrl);
            String scheme = StringUtils.trimToEmpty(uri.getScheme()).toLowerCase(Locale.ROOT);
            String host = StringUtils.trimToEmpty(uri.getHost()).toLowerCase(Locale.ROOT);
            if (!(Strings.CS.equals(scheme, "http") || Strings.CS.equals(scheme, "https"))
                    || host.isBlank()
                    || uri.getRawUserInfo() != null
                    || !uri.isAbsolute()
            ) {
                return Optional.empty();
            }

            int port = uri.getPort();
            boolean defaultPort = (Strings.CS.equals(scheme, "http") && port == 80)
                    || (Strings.CS.equals(scheme, "https") && port == 443);
            String path = StringUtils.defaultString(uri.getRawPath());
            if (Strings.CS.equals(path, "/")) {
                path = "";
            }
            URI keyUri = new URI(
                    scheme,
                    null,
                    host,
                    defaultPort ? -1 : port,
                    path,
                    uri.getRawQuery(),
                    null
            );
            return Optional.of(new NormalizedUrl(keyUri.toASCIIString(), displayUrl, host));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public record NormalizedUrl(String key, String displayUrl, String host) {
    }
}
