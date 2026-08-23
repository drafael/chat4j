package com.github.drafael.chat4j.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.drafael.chat4j.http.HttpExchangeOptions;
import com.github.drafael.chat4j.http.HttpTransport;
import com.github.drafael.chat4j.http.JavaNetHttpTransport;
import com.github.drafael.chat4j.http.JsonHttpClient;
import com.github.drafael.chat4j.json.JsonCodec;
import java.net.URI;
import com.github.drafael.chat4j.http.JavaNetHttpTransport.RedirectPolicy;
import java.time.Duration;
import java.util.Map;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

final class OpenRouterUsageClient {

    private static final URI KEY_URI = URI.create("https://openrouter.ai/api/v1/key");
    private static final URI CREDITS_URI = URI.create("https://openrouter.ai/api/v1/credits");
    private static final HttpExchangeOptions OPTIONS = new HttpExchangeOptions(Duration.ofSeconds(6), 0);
    private static final HttpTransport DEFAULT_TRANSPORT = JavaNetHttpTransport.create(Duration.ofSeconds(3), RedirectPolicy.NEVER);

    private final JsonHttpClient httpClient;

    OpenRouterUsageClient() {
        this(DEFAULT_TRANSPORT);
    }

    OpenRouterUsageClient(@NonNull HttpTransport transport) {
        this.httpClient = new JsonHttpClient(JsonCodec.standard(), transport);
    }

    Snapshot fetch(String apiKey) {
        if (StringUtils.isBlank(apiKey)) {
            return Snapshot.error("OPENROUTER_API_KEY not set");
        }
        try {
            Data keyData = requestData(KEY_URI, apiKey);
            double limit = number(keyData.limit());
            double usage = number(keyData.usage());
            double remainingFromUsage = !Double.isNaN(limit) && !Double.isNaN(usage)
                    ? Math.max(0, limit - usage)
                    : Double.NaN;
            double limitRemaining = number(keyData.limitRemaining());
            double remaining = !Double.isNaN(remainingFromUsage) ? remainingFromUsage : limitRemaining;
            int usedPercent = usedPercent(limit, usage, remaining);
            String note = StringUtils.isBlank(keyData.limitReset()) ? null : "Resets %s".formatted(keyData.limitReset());

            Double balance = null;
            try {
                Data creditsData = requestData(CREDITS_URI, apiKey);
                double totalCredits = number(creditsData.totalCredits());
                double totalUsage = number(creditsData.totalUsage());
                if (!Double.isNaN(totalCredits) && !Double.isNaN(totalUsage)) {
                    balance = Math.max(0, totalCredits - totalUsage);
                }
            } catch (Exception e) {
                String creditsError = firstLine(e.getMessage());
                note = note == null ? "Balance unavailable: %s".formatted(creditsError) : "%s • Balance unavailable".formatted(note);
            }
            return Snapshot.success(balance, limit, remaining, usedPercent, note);
        } catch (Exception e) {
            return Snapshot.error(firstLine(e.getMessage()));
        }
    }

    private Data requestData(URI uri, String apiKey) throws Exception {
        var response = httpClient.get(
                uri,
                Map.of("Authorization", "Bearer %s".formatted(apiKey)),
                OPTIONS,
                () -> false
        );
        if (!response.successful()) {
            throw new IllegalStateException("HTTP %d from %s".formatted(response.statusCode(), uri));
        }
        Envelope envelope = httpClient.read(response, Envelope.class, "Invalid response from %s".formatted(uri));
        if (envelope.data() == null) {
            throw new IllegalStateException("Missing data object in response from %s".formatted(uri));
        }
        return envelope.data();
    }

    private static int usedPercent(double limit, double usage, double remaining) {
        if (!Double.isNaN(limit) && limit > 0 && !Double.isNaN(usage)) {
            return clamp((int) Math.round((usage / limit) * 100d));
        }
        if (!Double.isNaN(limit) && limit > 0 && !Double.isNaN(remaining)) {
            return clamp((int) Math.round((1d - (remaining / limit)) * 100d));
        }
        return -1;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static String firstLine(String text) {
        if (StringUtils.isBlank(text)) {
            return "Unknown error";
        }
        String normalized = text.replace('\r', ' ').replace('\n', ' ').trim();
        return StringUtils.abbreviate(normalized, 180);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Envelope(Data data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Data(
            Object limit,
            Object usage,
            @JsonProperty("limit_remaining") Object limitRemaining,
            @JsonProperty("limit_reset") String limitReset,
            @JsonProperty("total_credits") Object totalCredits,
            @JsonProperty("total_usage") Object totalUsage
    ) {
    }

    record Snapshot(
            Double balance,
            double limit,
            double remaining,
            int usedPercent,
            String note,
            String errorMessage,
            long updatedAtEpochMs
    ) {
        static Snapshot success(Double balance, double limit, double remaining, int usedPercent, String note) {
            return new Snapshot(balance, limit, remaining, usedPercent, note, null, System.currentTimeMillis());
        }

        static Snapshot error(String message) {
            return new Snapshot(null, Double.NaN, Double.NaN, -1, null, message, System.currentTimeMillis());
        }
    }
}
