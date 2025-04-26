package com.chat4j.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * Response from the Ollama API
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OllamaResponse {

    private String model;
    private String createdAt;
    private OllamaMessage message;
    private boolean done;
    
    // For streaming responses
    private String content;
    
    // For /api/generate responses
    private String response;
}