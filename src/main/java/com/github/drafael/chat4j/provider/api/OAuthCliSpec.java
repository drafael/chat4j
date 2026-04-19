package com.github.drafael.chat4j.provider.api;

import java.util.List;
import static java.util.Collections.emptyList;

public record OAuthCliSpec(
    List<String> statusCommand,
    List<String> loginCommand,
    List<String> logoutCommand,
    List<String> tokenCommand
) {

    public OAuthCliSpec {
        statusCommand = sanitize(statusCommand);
        loginCommand = sanitize(loginCommand);
        logoutCommand = sanitize(logoutCommand);
        tokenCommand = sanitize(tokenCommand);
    }

    private static List<String> sanitize(List<String> command) {
        return command == null ? emptyList() : command.stream().map(String::trim).filter(part -> !part.isBlank()).toList();
    }
}
