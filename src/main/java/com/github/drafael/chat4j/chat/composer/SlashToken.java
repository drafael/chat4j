package com.github.drafael.chat4j.chat.composer;

record SlashToken(int startIndex, String query) {
    @Override
    public String toString() {
        return "SlashToken[startIndex=%d, query=<masked>]".formatted(startIndex);
    }
}
