package com.github.drafael.chat4j.http;

import java.time.Duration;
import lombok.NonNull;

public record HttpExchangeOptions(@NonNull Duration timeout, long maxResponseBytes) {
}
