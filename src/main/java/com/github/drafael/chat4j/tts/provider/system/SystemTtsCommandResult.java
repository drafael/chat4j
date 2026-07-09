package com.github.drafael.chat4j.tts.provider.system;

import org.apache.commons.lang3.StringUtils;

record SystemTtsCommandResult(int exitCode, String stdout, String stderr) {

    SystemTtsCommandResult {
        stdout = StringUtils.defaultString(stdout);
        stderr = StringUtils.defaultString(stderr);
    }

    boolean successful() {
        return exitCode == 0;
    }

    String safeErrorText() {
        String text = StringUtils.defaultIfBlank(stderr, stdout);
        return StringUtils.defaultIfBlank(StringUtils.abbreviate(StringUtils.normalizeSpace(text), 300), "command failed");
    }
}
