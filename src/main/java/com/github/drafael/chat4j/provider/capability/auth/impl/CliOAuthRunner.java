package com.github.drafael.chat4j.provider.capability.auth.impl;

import com.github.drafael.chat4j.provider.api.OAuthCliSpec;
import com.github.drafael.chat4j.provider.support.ProcessCommandSupport;
import org.apache.commons.lang3.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static java.util.Collections.emptyList;

public class CliOAuthRunner {

    private static final Duration STATUS_TIMEOUT = Duration.ofMillis(350);
    private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration AUTH_ACTION_TIMEOUT = Duration.ofMinutes(3);

    public OAuthStatus checkStatus(OAuthCliSpec spec) {
        if (spec == null || spec.statusCommand().isEmpty()) {
            return OAuthStatus.unavailable("OAuth status command is not configured");
        }

        CommandResult result = execute(spec.statusCommand(), STATUS_TIMEOUT);
        if (result.timedOut()) {
            return OAuthStatus.unauthorized("Status check timed out");
        }
        if (result.error() != null) {
            if (isCommandMissing(result.error())) {
                return OAuthStatus.unavailable(missingCommandMessage(spec.statusCommand()));
            }
            return OAuthStatus.unavailable("Unable to run status command: %s".formatted(firstLine(result.error().getMessage())));
        }

        String output = result.output().toLowerCase(Locale.ROOT);
        if (result.exitCode() != 0 || containsUnauthorizedHint(output)) {
            String reason = output.isBlank() ? "Unauthorized." : "Unauthorized. %s".formatted(firstLine(output));
            return OAuthStatus.unauthorized(reason);
        }

        return OAuthStatus.authorized("Authorized.");
    }

    public OAuthActionResult login(OAuthCliSpec spec) {
        return runAction(spec == null ? emptyList() : spec.loginCommand(), "Login");
    }

    public OAuthActionResult logout(OAuthCliSpec spec) {
        return runAction(spec == null ? emptyList() : spec.logoutCommand(), "Logout");
    }

    public String resolveBearerToken(OAuthCliSpec spec) {
        String token = resolveBearerTokenOrNull(spec);
        if (StringUtils.isBlank(token)) {
            throw new IllegalStateException("OAuth token command is not configured");
        }
        return token;
    }

    public String resolveBearerTokenOrNull(OAuthCliSpec spec) {
        if (spec == null) {
            return null;
        }

        if (!spec.tokenCommand().isEmpty()) {
            CommandResult result = execute(spec.tokenCommand(), TOKEN_TIMEOUT);
            if (result.timedOut() || result.error() != null || result.exitCode() != 0) {
                return null;
            }

            String token = firstNonBlankLine(result.output());
            return token == null ? null : stripBearerPrefix(token);
        }

        if (isCodexSpec(spec)) {
            return readCodexTokenFromAuthJson();
        }

        return null;
    }

    private OAuthActionResult runAction(List<String> command, String actionName) {
        if (command == null || command.isEmpty()) {
            return OAuthActionResult.failure("%s command is not configured".formatted(actionName));
        }

        CommandResult result = execute(command, AUTH_ACTION_TIMEOUT);
        if (result.timedOut()) {
            return OAuthActionResult.failure("%s timed out".formatted(actionName));
        }
        if (result.error() != null) {
            if (isCommandMissing(result.error())) {
                return OAuthActionResult.failure(missingCommandMessage(command));
            }
            return OAuthActionResult.failure("%s failed: %s".formatted(actionName, firstLine(result.error().getMessage())));
        }
        if (result.exitCode() != 0) {
            String details = result.output().isBlank() ? "" : " - %s".formatted(firstLine(result.output()));
            return OAuthActionResult.failure("%s failed (exit %d)%s".formatted(actionName, result.exitCode(), details));
        }

        return OAuthActionResult.success("%s completed".formatted(actionName));
    }

    private CommandResult execute(List<String> command, Duration timeout) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        ProcessCommandSupport.applyShellEnvironment(processBuilder);

        try {
            Process process = processBuilder.start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return readAll(process);
                } catch (IOException e) {
                    return "";
                }
            });

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(-1, outputFuture.join(), true, null);
            }
            return new CommandResult(process.exitValue(), outputFuture.join(), false, null);
        } catch (Exception e) {
            return new CommandResult(-1, "", false, e);
        }
    }

    private String readAll(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().reduce("", (left, right) -> left.isEmpty() ? right : "%s\n%s".formatted(left, right));
        }
    }

    private boolean containsUnauthorizedHint(String output) {
        return output.contains("unauthorized")
                || output.contains("not logged in")
                || output.contains("not authenticated")
                || output.contains("login required")
                || output.contains("please login");
    }

    private boolean isCodexSpec(OAuthCliSpec spec) {
        return !spec.statusCommand().isEmpty()
                && "codex".equalsIgnoreCase(spec.statusCommand().getFirst());
    }

    private boolean isCommandMissing(Exception error) {
        if (!(error instanceof IOException ioException)) {
            return false;
        }

        String message = ioException.getMessage();
        if (StringUtils.isBlank(message)) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("error=2")
                || normalized.contains("no such file or directory")
                || normalized.contains("cannot find the file");
    }

    private String missingCommandMessage(List<String> command) {
        String executable = executableName(command);
        return switch (executable) {
            case "gh" -> "GitHub CLI ('gh') is not installed or not on PATH. Install it from https://cli.github.com/";
            case "codex" -> "OpenAI Codex CLI ('codex') is not installed or not on PATH.";
            default -> "Required CLI command '%s' is not installed or not on PATH.".formatted(executable);
        };
    }

    private String executableName(List<String> command) {
        if (command == null || command.isEmpty()) {
            return "<command>";
        }

        String executable = command.getFirst();
        if (StringUtils.isBlank(executable)) {
            return "<command>";
        }

        return executable.trim();
    }

    private String readCodexTokenFromAuthJson() {
        try {
            Path authJson = Path.of(System.getProperty("user.home"), ".codex", "auth.json");
            if (!Files.exists(authJson)) {
                return null;
            }

            String content = Files.readString(authJson, StandardCharsets.UTF_8);
            String accessToken = extractJsonField(content, "access_token");
            if (StringUtils.isNotBlank(accessToken)) {
                return accessToken;
            }

            String apiKey = extractJsonField(content, "OPENAI_API_KEY");
            return StringUtils.isBlank(apiKey) ? null : apiKey;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonField(String content, String fieldName) {
        Pattern pattern = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]+)\"".formatted(Pattern.quote(fieldName)));
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String stripBearerPrefix(String token) {
        return token.regionMatches(true, 0, "Bearer ", 0, 7)
                ? token.substring(7).trim()
                : token;
    }

    private String firstLine(String text) {
        if (StringUtils.isBlank(text)) {
            return "Unknown error";
        }
        int index = text.indexOf('\n');
        return index < 0 ? text.trim() : text.substring(0, index).trim();
    }

    private String firstNonBlankLine(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }

        return text.lines()
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    public record OAuthStatus(boolean authorized, boolean cliAvailable, String message) {

        public static OAuthStatus authorized(String message) {
            return new OAuthStatus(true, true, message);
        }

        public static OAuthStatus unauthorized(String message) {
            return new OAuthStatus(false, true, message);
        }

        public static OAuthStatus unavailable(String message) {
            return new OAuthStatus(false, false, message);
        }
    }

    public record OAuthActionResult(boolean success, String message) {

        public static OAuthActionResult success(String message) {
            return new OAuthActionResult(true, message);
        }

        public static OAuthActionResult failure(String message) {
            return new OAuthActionResult(false, message);
        }
    }

    private record CommandResult(int exitCode, String output, boolean timedOut, Exception error) {
    }
}
