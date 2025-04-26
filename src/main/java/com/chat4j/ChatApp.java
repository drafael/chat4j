package com.chat4j;

import com.chat4j.ui.ChatMainFrame;
import com.formdev.flatlaf.intellijthemes.materialthemeuilite.FlatMaterialDarkerIJTheme;

import javax.swing.*;

/**
 * Main entry point for the Chat4J application
 */
public class ChatApp {
    public static void main(String[] args) {
        // Set up the FlatLaf Material Design Dark theme
        FlatMaterialDarkerIJTheme.setup();
        
        // Additional UI tweaks
        UIManager.put("Button.arc", 8);
        UIManager.put("Component.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        
        // Launch UI
        SwingUtilities.invokeLater(() -> {
            try {
                ChatMainFrame mainFrame = new ChatMainFrame();
                mainFrame.setVisible(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}