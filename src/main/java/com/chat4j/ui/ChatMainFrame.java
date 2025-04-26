package com.chat4j.ui;

import com.chat4j.client.OllamaClient;
import com.chat4j.model.ChatMessage;
import com.chat4j.markdown.MarkdownRenderer;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Main application window for the Chat4J application
 */
public class ChatMainFrame extends JFrame {
    private JTextArea chatArea;
    private JTextField messageField;
    private JButton sendButton;
    private OllamaClient client;
    private MarkdownRenderer markdownRenderer;

    public ChatMainFrame() {
        // Initialize the client and renderer
        client = new OllamaClient();
        markdownRenderer = new MarkdownRenderer();
        
        // Set up the frame
        setTitle("Chat4J");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        
        // Create layout
        setupUI();
    }
    
    private void setupUI() {
        // Chat display area
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        
        JScrollPane scrollPane = new JScrollPane(chatArea);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        
        // Input area
        messageField = new JTextField();
        messageField.addActionListener(e -> sendMessage());
        
        sendButton = new JButton("Send");
        sendButton.addActionListener(e -> sendMessage());
        
        // Layout
        JPanel inputPanel = new JPanel(new BorderLayout());
        inputPanel.add(messageField, BorderLayout.CENTER);
        inputPanel.add(sendButton, BorderLayout.EAST);
        
        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(scrollPane, BorderLayout.CENTER);
        getContentPane().add(inputPanel, BorderLayout.SOUTH);
    }
    
    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) {
            return;
        }
        
        // Display user message
        chatArea.append("You: " + message + "\n\n");
        messageField.setText("");
        
        // Create a user message and indicate that processing is happening
        ChatMessage userMessage = new ChatMessage(ChatMessage.Role.USER, message);
        SwingUtilities.invokeLater(() -> {
            chatArea.append("AI: ");
        });
        
        // Set up a list of messages for the conversation
        java.util.List<ChatMessage> messages = new java.util.ArrayList<>();
        messages.add(userMessage);
        
        // Disable input while processing
        messageField.setEnabled(false);
        sendButton.setEnabled(false);
        
        // Use the streaming API to get responses chunk by chunk
        client.streamChatRequest(
            "cogito:8b", // Default model name - could be made configurable
            messages,
            0.7, // Default temperature - could be made configurable
            // Handle each chunk as it arrives
            chunk -> SwingUtilities.invokeLater(() -> {
                chatArea.append(chunk);
                // Auto-scroll to the bottom of the chat area
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
            }),
            // Handle complete message
            completeMessage -> SwingUtilities.invokeLater(() -> {
                chatArea.append("\n\n");
                // Re-enable input after processing is complete
                messageField.setEnabled(true);
                sendButton.setEnabled(true);
                messageField.requestFocus();
                // Auto-scroll to the bottom of the chat area
                chatArea.setCaretPosition(chatArea.getDocument().getLength());
            })
        );
    }
}