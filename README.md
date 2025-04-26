# Chat4j

A Java desktop application that provides a chat interface for interacting with Ollama language models.

## Overview

Chat4j is a simple, elegant desktop application built with Java Swing that allows you to chat with local language models running on Ollama. It features:

- Modern dark-themed UI with Material Design elements
- Markdown rendering support
- Streaming responses for a natural chat experience
- Support for both Ollama's `/api/chat` and `/api/generate` endpoints

## Requirements

- Java 21 or newer
- Maven 3.6+ (for building)
- [Ollama](https://ollama.ai/) running locally on the default port (11434)
- A language model loaded in Ollama (the default is "cogito:8b" but you can modify this in the code)

## Building the Application

To build the application, run the following Maven command from the project root directory:

```bash
mvn clean package
```

This will create two JAR files in the `target` directory:
- `chat4j-1.0-SNAPSHOT.jar` - The application JAR without dependencies
- `chat4j-1.0-SNAPSHOT-jar-with-dependencies.jar` - The application JAR with all dependencies included

## Running the Application

After building, you can run the application using:

```bash
java -jar target/chat4j-1.0-SNAPSHOT-jar-with-dependencies.jar
```

Alternatively, if you have the project open in an IDE like IntelliJ IDEA or Eclipse, you can run the `ChatApp` class directly.

## Usage

1. Make sure Ollama is running on your local machine at http://localhost:11434
2. Launch the Chat4j application
3. Type your message in the text field at the bottom
4. Press Enter or click the Send button
5. The AI response will stream back in real-time

## Troubleshooting

If you encounter issues:

1. Verify Ollama is running with `curl http://localhost:11434/api/version`
2. Ensure you have at least one model downloaded in Ollama (`ollama list`)
3. Check that port 11434 is accessible and not blocked by a firewall

## Development

The project is structured as follows:

- `com.chat4j.ChatApp` - Main entry point
- `com.chat4j.ui.ChatMainFrame` - Main UI component
- `com.chat4j.client.OllamaClient` - Client for communicating with Ollama API
- `com.chat4j.markdown.MarkdownRenderer` - Utility for rendering markdown responses
- `com.chat4j.model.*` - Data models for the application

## License

This project is open source.