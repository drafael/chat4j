package com.github.drafael.chat4j.chat;

record SkillCommand(String name, String description) {
    @Override
    public String toString() {
        return "SkillCommand[name=%s, description=<masked>]".formatted(name);
    }
}
