package com.github.drafael.chat4j.chat.export.pdf;

import java.nio.file.Path;
import java.util.regex.Pattern;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

public final class PdfExportFileNames {

    private static final Pattern WINDOWS_RESERVED_NAME = Pattern.compile(
            "(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$"
    );

    private PdfExportFileNames() {
    }

    public static Path ensurePdfExtension(@NonNull Path path) {
        if (path.getFileName() == null) {
            return path;
        }
        String fileName = path.getFileName().toString();
        return Strings.CI.endsWith(fileName, ".pdf")
                ? path
                : path.resolveSibling("%s.pdf".formatted(fileName));
    }

    public static String suggestedFileName(String title) {
        String base = StringUtils.defaultIfBlank(title, "Chat4J Conversation")
                .replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]+", "-")
                .replaceAll("\\s+", " ")
                .trim();
        if (StringUtils.isBlank(base)) {
            base = "Chat4J Conversation";
        }
        if (WINDOWS_RESERVED_NAME.matcher(base).matches()) {
            base = "%s-conversation".formatted(base);
        }
        return "%s.pdf".formatted(StringUtils.abbreviate(base, 120));
    }
}
