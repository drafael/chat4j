package com.chat4j.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * Represents a message in the format required by the Ollama API
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OllamaMessage {
    private String role;
    private String content;
}