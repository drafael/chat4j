package com.chat4j.model;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * Represents a message in the chat interface
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    public enum Role {
        USER, ASSISTANT, SYSTEM
    }

    private Role role;
    private String content;
    private LocalDateTime timestamp;


    public ChatMessage(Role role, String message) {
        this(role, message, LocalDateTime.now());
    }

    /**
     * Converts the message to the format required by the Ollama API
     */
    public OllamaMessage toOllamaMessage() {
        return new OllamaMessage(role.toString().toLowerCase(), content);
    }
}