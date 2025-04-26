package com.chat4j.model;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/**
 * Request payload for the Ollama API
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OllamaRequest {

    private String model;
    private List<OllamaMessage> messages;
    private boolean stream;
    private Double temperature;

}