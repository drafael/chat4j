package com.github.drafael.chat4j.chat.composer;

record SkillCommand(String name, String description) {
    @Override
    public String toString() {
        return "SkillCommand[name=%s, description=<masked>]".formatted(name);
    }
}
