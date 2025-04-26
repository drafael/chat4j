package com.chat4j.markdown;

import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.*;
import java.util.Arrays;

/**
 * Utility for rendering Markdown content in the chat UI
 */
public class MarkdownRenderer {
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final HTMLEditorKit editorKit;
    
    public MarkdownRenderer() {
        // Configure Flexmark with extensions
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create()
        ));
        
        parser = Parser.builder(options).build();
        renderer = HtmlRenderer.builder(options).build();
        
        // Configure HTML styling
        editorKit = new HTMLEditorKit();
        StyleSheet styleSheet = editorKit.getStyleSheet();
        
        // Base styles
        styleSheet.addRule("body { font-family: 'Segoe UI', Arial, sans-serif; margin: 0; padding: 0; }");
        
        // Code blocks and inline code
        styleSheet.addRule("pre { background-color: #f4f4f4; border-radius: 5px; padding: 10px; overflow-x: auto; }");
        styleSheet.addRule("code { font-family: 'Courier New', monospace; background-color: #f4f4f4; padding: 2px 4px; border-radius: 3px; }");
        
        // Tables
        styleSheet.addRule("table { border-collapse: collapse; width: 100%; margin: 10px 0; }");
        styleSheet.addRule("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }");
        styleSheet.addRule("th { background-color: #f2f2f2; }");
        
        // Headings
        styleSheet.addRule("h1, h2, h3, h4, h5, h6 { margin-top: 15px; margin-bottom: 10px; }");
        
        // Lists
        styleSheet.addRule("ul, ol { padding-left: 25px; }");
        
        // Links
        styleSheet.addRule("a { color: #0066cc; text-decoration: none; }");
        styleSheet.addRule("a:hover { text-decoration: underline; }");
    }
    
    /**
     * Render Markdown content to a JEditorPane component
     */
    public JEditorPane renderMarkdown(String markdown) {
        JEditorPane editorPane = new JEditorPane();
        editorPane.setEditorKit(editorKit);
        editorPane.setEditable(false);
        
        // For dark themes, set appropriate text and background color
        editorPane.setBackground(new Color(45, 45, 45));
        editorPane.setForeground(new Color(230, 230, 230));
        
        // Enable smooth scrolling
        editorPane.putClientProperty("JEditorPane.honorDisplayProperties", Boolean.TRUE);
        
        // Parse and render the markdown to HTML
        Node document = parser.parse(markdown);
        String html = renderer.render(document);
        
        // Add wrapper with dark theme adjustments
        String darkThemeAdjustedHtml = 
                "<html><body style='color: #e6e6e6; background-color: #2d2d2d;'>" + 
                html + 
                "</body></html>";
        
        editorPane.setText(darkThemeAdjustedHtml);
        
        return editorPane;
    }
}