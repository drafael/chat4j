package com.github.drafael.chat4j.provider.capability.auth.impl;

import com.github.drafael.chat4j.provider.api.OAuthCliSpec;

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

public class CliOAuthRunner {

    private static final Duration STATUS_TIMEOUT = Duration.ofMillis(350);
    private static final Duration TOKEN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration AUTH_ACTION_TIMEOUT = Duration.ofMinutes(3);

    public OAuthStatus checkStatus(OAuthCliSpec spec) {
        if (spec == null || spec.statusCommand().isEmpty()) {
            return OAuthStatus.unauthorized("OAuth status command is not configured");
        }

        CommandResult result = execute(spec.statusCommand(), STATUS_TIMEOUT);
        if (result.timedOut()) {
            return OAuthStatus.unauthorized("Status check timed out");
        }
        if (result.error() != null) {
            return OAuthStatus.unauthorized(result.error().getMessage());
        }

        String output = result.output().toLowerCase(Locale.ROOT);
        if (result.exitCode() != 0 || containsUnauthorizedHint(output)) {
            String reason = output.isBlank() ? "Unauthorized." : "Unauthorized. " + firstLine(output);
            return OAuthStatus.unauthorized(reason);
        }

        return OAuthStatus.authorized("Authorized.");
    }

    public OAuthActionResult login(OAuthCliSpec spec) {
        return runAction(spec == null ? List.of() : spec.loginCommand(), "Login");
    }

    public OAuthActionResult logout(OAuthCliSpec spec) {
        return runAction(spec == null ? List.of() : spec.logoutCommand(), "Logout");
    }

    public String resolveBearerToken(OAuthCliSpec spec) {
        String token = resolveBearerTokenOrNull(spec);
        if (token == null || token.isBlank()) {
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
            return OAuthActionResult.failure(actionName + " command is not configured");
        }

        CommandResult result = execute(command, AUTH_ACTION_TIMEOUT);
        if (result.timedOut()) {
            return OAuthActionResult.failure(actionName + " timed out");
        }
        if (result.error() != null) {
            return OAuthActionResult.failure(actionName + " failed: " + result.error().getMessage());
        }
        if (result.exitCode() != 0) {
            String details = result.output().isBlank() ? "" : " - " + firstLine(result.output());
            return OAuthActionResult.failure(actionName + " failed (exit " + result.exitCode() + ")" + details);
        }

        return OAuthActionResult.success(actionName + " completed");
    }

    private CommandResult execute(List<String> command, Duration timeout) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

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
            return reader.lines().reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);
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

    private String readCodexTokenFromAuthJson() {
        try {
            Path authJson = Path.of(System.getProperty("user.home"), ".codex", "auth.json");
            if (!Files.exists(authJson)) {
                return null;
            }

            String content = Files.readString(authJson, StandardCharsets.UTF_8);
            String accessToken = extractJsonField(content, "access_token");
            if (accessToken != null && !accessToken.isBlank()) {
                return accessToken;
            }

            String apiKey = extractJsonField(content, "OPENAI_API_KEY");
            return apiKey == null || apiKey.isBlank() ? null : apiKey;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonField(String content, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String stripBearerPrefix(String token) {
        return token.regionMatches(true, 0, "Bearer ", 0, 7)
                ? token.substring(7).trim()
                : token;
    }

    private String firstLine(String text) {
        int index = text.indexOf('\n');
        return index < 0 ? text.trim() : text.substring(0, index).trim();
    }

    private String firstNonBlankLine(String text) {
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse(null);
    }

    public record OAuthStatus(boolean authorized, String message) {

        public static OAuthStatus authorized(String message) {
            return new OAuthStatus(true, message);
        }

        public static OAuthStatus unauthorized(String message) {
            return new OAuthStatus(false, message);
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
