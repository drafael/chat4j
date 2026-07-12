package com.github.drafael.chat4j.settings;

public record ApiTokenChange(String canonicalTokenId, boolean cleared, boolean savedOverride) {
}
