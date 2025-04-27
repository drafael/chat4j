package com.github.drafael.chat4j.sidebar;

import java.time.LocalDateTime;
import java.util.UUID;

public record ConversationItem(
    UUID id,
    String title,
    String provider,
    String model,
    boolean isFavorite,
    LocalDateTime updatedAt
) {}
