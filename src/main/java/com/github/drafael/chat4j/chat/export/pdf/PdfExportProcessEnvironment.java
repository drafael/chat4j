package com.github.drafael.chat4j.chat.export.pdf;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;

final class PdfExportProcessEnvironment {

    private static final Set<String> BASE_NAMES = Set.of(
            "PATH",
            "PATHEXT",
            "HOME",
            "USERPROFILE",
            "TMP",
            "TEMP",
            "TMPDIR",
            "SYSTEMROOT",
            "WINDIR",
            "COMSPEC",
            "OS",
            "LANG",
            "LC_ALL",
            "LC_CTYPE",
            "LC_NUMERIC",
            "LC_TIME",
            "LC_COLLATE",
            "LC_MONETARY",
            "LC_MESSAGES",
            "LC_PAPER",
            "LC_NAME",
            "LC_ADDRESS",
            "LC_TELEPHONE",
            "LC_MEASUREMENT",
            "LC_IDENTIFICATION",
            "USER",
            "LOGNAME"
    );
    private static final Set<String> FONT_NAMES = Set.of(
            "FONTCONFIG_PATH",
            "FONTCONFIG_FILE"
    );
    private static final Set<String> MERMAID_NAMES = Set.of(
            "DISPLAY",
            "WAYLAND_DISPLAY",
            "XDG_RUNTIME_DIR",
            "XDG_CACHE_HOME",
            "DBUS_SESSION_BUS_ADDRESS",
            "PUPPETEER_EXECUTABLE_PATH",
            "PUPPETEER_CACHE_DIR"
    );
    private static final Set<String> PUBLICATION_NAMES = Set.of(
            "TEXINPUTS",
            "LUAINPUTS",
            "BIBINPUTS",
            "BSTINPUTS",
            "OSFONTDIR",
            "SOURCE_DATE_EPOCH",
            "TEXMFCNF",
            "TEXMFHOME",
            "TEXMFVAR",
            "TEXMFCACHE",
            "TEXMFCONFIG",
            "TEXMFSYSVAR",
            "TEXMFSYSCONFIG"
    );

    private PdfExportProcessEnvironment() {
    }

    static Map<String, String> forMermaid(Map<String, String> source) {
        return filter(source, MERMAID_NAMES);
    }

    static Map<String, String> forPublication(Map<String, String> source) {
        return filter(source, PUBLICATION_NAMES);
    }

    private static Map<String, String> filter(Map<String, String> source, Set<String> toolNames) {
        Map<String, String> safe = new LinkedHashMap<>();
        source.forEach((name, value) -> {
            String normalized = StringUtils.upperCase(name, Locale.ROOT);
            if (BASE_NAMES.contains(normalized)
                    || FONT_NAMES.contains(normalized)
                    || toolNames.contains(normalized)
            ) {
                safe.put(name, value);
            }
        });
        return Map.copyOf(safe);
    }
}
