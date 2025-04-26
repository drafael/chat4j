package com.chat4j.client;

import com.chat4j.model.ChatMessage;
import com.chat4j.model.OllamaMessage;
import com.chat4j.model.OllamaRequest;
import com.chat4j.model.OllamaResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Client for communicating with the Ollama API
 */
public class OllamaClient {
    private static final Logger logger = LoggerFactory.getLogger(OllamaClient.class);
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    
    public OllamaClient() {
        this(DEFAULT_BASE_URL);
    }
    
    public OllamaClient(String baseUrl) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.baseUrl = baseUrl;
    }
    
    /**
     * Send a non-streaming chat request to the Ollama API
     * 
     * @param modelName The name of the model to use
     * @param messages The messages to send
     * @param temperature The temperature parameter (randomness)
     * @return The model's response
     */
    public ChatMessage sendChatRequest(String modelName, List<ChatMessage> messages, double temperature) throws IOException {
        List<OllamaMessage> ollamaMessages = messages.stream()
                .map(ChatMessage::toOllamaMessage)
                .collect(Collectors.toList());
        
        OllamaRequest request = new OllamaRequest(modelName, ollamaMessages, false, temperature);
        String requestJson = objectMapper.writeValueAsString(request);
        
        // First try /chat endpoint
        RequestBody body = RequestBody.create(requestJson, JSON);
        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/api/chat")
                .post(body)
                .build();
        
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (response.isSuccessful()) {
                OllamaResponse ollamaResponse = objectMapper.readValue(response.body().string(), OllamaResponse.class);
                return new ChatMessage(ChatMessage.Role.ASSISTANT, ollamaResponse.getMessage().getContent());
            } else if (response.code() == 404) {
                // Fallback to the /generate endpoint if /chat is not found
                logger.info("Falling back to /generate endpoint");
                return sendGenerateRequest(modelName, messages, temperature);
            } else {
                throw new IOException("Unexpected response code: " + response);
            }
        }
    }
    
    /**
     * Fallback method to use the /generate endpoint
     */
    private ChatMessage sendGenerateRequest(String modelName, List<ChatMessage> messages, double temperature) throws IOException {
        // Format the messages into a single prompt
        StringBuilder prompt = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.Role.SYSTEM) {
                prompt.append("System: ").append(message.getContent()).append("\n\n");
            } else if (message.getRole() == ChatMessage.Role.USER) {
                prompt.append("User: ").append(message.getContent()).append("\n\n");
            } else if (message.getRole() == ChatMessage.Role.ASSISTANT) {
                prompt.append("Assistant: ").append(message.getContent()).append("\n\n");
            }
        }
        
        // Create a request object and use ObjectMapper to properly serialize it
        OllamaRequest generateRequest = new OllamaRequest(
            modelName,
            null, // no messages for /generate
            false, // not streaming
            temperature
        );
        
        // Add the prompt separately to avoid serialization issues
        String jsonRequest = objectMapper.writeValueAsString(generateRequest);
        // Remove the closing brace
        jsonRequest = jsonRequest.substring(0, jsonRequest.length() - 1);
        // Add the prompt field and close the JSON object
        jsonRequest += ",\"prompt\":" + objectMapper.writeValueAsString(prompt.toString()) + "}";
        
        RequestBody body = RequestBody.create(jsonRequest, JSON);
        Request httpRequest = new Request.Builder()
                .url(baseUrl + "/api/generate")
                .post(body)
                .build();
        
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 404) {
                    logger.error("Endpoint not found: " + httpRequest.url());
                } else {
                    logger.error("Unexpected response code: " + response.code() + ", message: " + response.message());
                }
                throw new IOException("Unexpected response code: " + response);
            }
            
            // Parse the response
            String responseText = response.body().string();
            try {
                OllamaResponse ollamaResponse = objectMapper.readValue(responseText, OllamaResponse.class);
                if (ollamaResponse.getMessage() != null) {
                    return new ChatMessage(ChatMessage.Role.ASSISTANT, ollamaResponse.getMessage().getContent());
                } else {
                    // For /generate, the response might be in "response" field rather than "message"
                    return new ChatMessage(ChatMessage.Role.ASSISTANT, ollamaResponse.getResponse());
                }
            } catch (Exception e) {
                logger.error("Error parsing response: " + responseText, e);
                throw new IOException("Error parsing Ollama response", e);
            }
        }
    }
    
    /**
     * Send a streaming chat request to the Ollama API
     * 
     * @param modelName The name of the model to use
     * @param messages The messages to send
     * @param temperature The temperature parameter (randomness)
     * @param onChunk Callback for each chunk of the streamed response
     * @param onComplete Callback when the streaming is complete
     */
    public void streamChatRequest(
            String modelName, 
            List<ChatMessage> messages, 
            double temperature,
            Consumer<String> onChunk,
            Consumer<ChatMessage> onComplete
    ) {
        List<OllamaMessage> ollamaMessages = messages.stream()
                .map(ChatMessage::toOllamaMessage)
                .collect(Collectors.toList());
        
        OllamaRequest request = new OllamaRequest(modelName, ollamaMessages, true, temperature);
        
        try {
            String requestJson = objectMapper.writeValueAsString(request);
            RequestBody body = RequestBody.create(requestJson, JSON);
            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/api/chat")
                    .post(body)
                    .build();
            
            StringBuilder fullResponse = new StringBuilder();
            
            httpClient.newCall(httpRequest).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    logger.error("Stream request failed", e);
                    // Try fallback to generate if chat fails
                    streamGenerateRequest(modelName, messages, temperature, onChunk, onComplete);
                }
                
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.code() == 404) {
                        logger.error("Streaming endpoint not found: " + call.request().url());
                        streamGenerateRequest(modelName, messages, temperature, onChunk, onComplete);
                        return;
                    }
                    
                    if (!response.isSuccessful()) {
                        logger.error("Unexpected response code during streaming: " + response.code() + ", message: " + response.message());
                        return;
                    }
                    
                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful()) {
                            logger.error("Unexpected response code: " + response);
                            return;
                        }
                        
                        try (okio.BufferedSource source = responseBody.source()) {
                            while (!source.exhausted()) {
                                String line = source.readUtf8Line();
                                if (line != null && !line.isEmpty()) {
                                    OllamaResponse chunk = objectMapper.readValue(line, OllamaResponse.class);
                                    
                                    if (chunk.getContent() != null) {
                                        String content = chunk.getContent();
                                        fullResponse.append(content);
                                        onChunk.accept(content);
                                    } else if (chunk.getMessage() != null && chunk.getMessage().getContent() != null) {
                                        String content = chunk.getMessage().getContent();
                                        fullResponse.append(content);
                                        onChunk.accept(content);
                                    }
                                    
                                    if (chunk.isDone()) {
                                        ChatMessage finalMessage = new ChatMessage(
                                                ChatMessage.Role.ASSISTANT, 
                                                fullResponse.toString()
                                        );
                                        onComplete.accept(finalMessage);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            });
        } catch (Exception e) {
            logger.error("Error in stream request", e);
        }
    }
    
    /**
     * Fallback method to stream using the /generate endpoint
     */
    private void streamGenerateRequest(
            String modelName, 
            List<ChatMessage> messages, 
            double temperature,
            Consumer<String> onChunk,
            Consumer<ChatMessage> onComplete
    ) {
        try {
            // Format the messages into a single prompt
            StringBuilder prompt = new StringBuilder();
            for (ChatMessage message : messages) {
                if (message.getRole() == ChatMessage.Role.SYSTEM) {
                    prompt.append("System: ").append(message.getContent()).append("\n\n");
                } else if (message.getRole() == ChatMessage.Role.USER) {
                    prompt.append("User: ").append(message.getContent()).append("\n\n");
                } else if (message.getRole() == ChatMessage.Role.ASSISTANT) {
                    prompt.append("Assistant: ").append(message.getContent()).append("\n\n");
                }
            }
            
            // Create a request object and use ObjectMapper to properly serialize it
            OllamaRequest generateRequest = new OllamaRequest(
                modelName,
                null, // no messages for /generate
                true, // streaming
                temperature
            );
            
            // Add the prompt separately to avoid serialization issues
            String jsonRequest = objectMapper.writeValueAsString(generateRequest);
            // Remove the closing brace
            jsonRequest = jsonRequest.substring(0, jsonRequest.length() - 1);
            // Add the prompt field and close the JSON object
            jsonRequest += ",\"prompt\":" + objectMapper.writeValueAsString(prompt.toString()) + "}";
            
            RequestBody body = RequestBody.create(jsonRequest, JSON);
            Request httpRequest = new Request.Builder()
                    .url(baseUrl + "/api/generate")
                    .post(body)
                    .build();
            
            StringBuilder fullResponse = new StringBuilder();
            
            httpClient.newCall(httpRequest).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    logger.error("Stream generate request failed", e);
                }
                
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (ResponseBody responseBody = response.body()) {
                        if (!response.isSuccessful()) {
                            logger.error("Unexpected response code: " + response);
                            return;
                        }
                        
                        try (okio.BufferedSource source = responseBody.source()) {
                            while (!source.exhausted()) {
                                String line = source.readUtf8Line();
                                if (line != null && !line.isEmpty()) {
                                    OllamaResponse chunk = objectMapper.readValue(line, OllamaResponse.class);
                                    
                                    if (chunk.getContent() != null) {
                                        String content = chunk.getContent();
                                        fullResponse.append(content);
                                        onChunk.accept(content);
                                    } else if (chunk.getResponse() != null) {
                                        String content = chunk.getResponse();
                                        fullResponse.append(content);
                                        onChunk.accept(content);
                                    }
                                    
                                    if (chunk.isDone()) {
                                        ChatMessage finalMessage = new ChatMessage(
                                                ChatMessage.Role.ASSISTANT, 
                                                fullResponse.toString()
                                        );
                                        onComplete.accept(finalMessage);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            });
        } catch (Exception e) {
            logger.error("Error in stream generate request", e);
        }
    }
}
