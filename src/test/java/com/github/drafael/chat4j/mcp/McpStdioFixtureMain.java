package com.github.drafael.chat4j.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptyMap;

public final class McpStdioFixtureMain {

    private static final ObjectMapper JSON = new ObjectMapper();

    private McpStdioFixtureMain() {
    }

    public static void main(String[] args) throws Exception {
        List<String> arguments = List.of(args);
        if (arguments.contains("--spawn-term-resistant-child")) {
            int option = arguments.indexOf("--spawn-term-resistant-child");
            Process child = new ProcessBuilder("sh", "-c", "trap '' TERM; sleep 60").start();
            Files.writeString(Path.of(arguments.get(option + 1)), Long.toString(child.pid()), StandardCharsets.UTF_8);
        }
        if (arguments.contains("--spawn-term-resistant-child-on-term")) {
            int option = arguments.indexOf("--spawn-term-resistant-child-on-term");
            Path childPidFile = Path.of(arguments.get(option + 1));
            Path readyFile = Path.of(arguments.get(option + 2));
            Path script = childPidFile.resolveSibling("term-spawn-%d.sh".formatted(ProcessHandle.current().pid()));
            Files.writeString(
                    script,
                    """
                    trap 'trap "" TERM; sleep 60 & echo $! > "$1"; sleep 1; exit 0' TERM
                    echo ready > "$2"
                    while :; do sleep 1; done
                    """,
                    StandardCharsets.UTF_8
            );
            new ProcessBuilder("sh", script.toString(), childPidFile.toString(), readyFile.toString()).start();
            awaitFile(readyFile);
        }
        if (arguments.contains("--write-pid")) {
            int option = arguments.indexOf("--write-pid");
            Files.writeString(
                    Path.of(arguments.get(option + 1)),
                    Long.toString(ProcessHandle.current().pid()),
                    StandardCharsets.UTF_8
            );
        }
        if (arguments.contains("--never-read")) {
            new CountDownLatch(1).await();
            return;
        }
        boolean crlf = arguments.contains("--crlf");
        boolean exitAfterList = arguments.contains("--exit-after-list");
        try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode request = JSON.readTree(line);
                if (!request.has("id")) {
                    continue;
                }
                String method = request.path("method").asText();
                Object id = JSON.convertValue(request.path("id"), Object.class);
                Map<String, Object> result = switch (method) {
                    case "initialize" -> Map.of(
                            "protocolVersion", "2025-06-18",
                            "capabilities", Map.of("tools", emptyMap()),
                            "serverInfo", Map.of("name", "stdio-test", "version", "1")
                    );
                    case "tools/list" -> Map.of("tools", List.of(Map.of(
                            "name", "pid",
                            "description", "Return the fixture process ID",
                            "inputSchema", Map.of("type", "object", "properties", emptyMap())
                    )));
                    case "tools/call" -> Map.of(
                            "content", List.of(Map.of(
                                    "type", "text",
                                    "text", arguments.contains("--echo-env")
                                            ? environmentDiagnostic(arguments)
                                            : Long.toString(ProcessHandle.current().pid())
                            )),
                            "isError", false
                    );
                    case "ping" -> emptyMap();
                    default -> emptyMap();
                };
                String response = JSON.writeValueAsString(Map.of(
                        "jsonrpc", "2.0",
                        "id", id,
                        "result", result
                ));
                System.out.print(response + (crlf ? "\r\n" : "\n"));
                System.out.flush();
                if (exitAfterList && "tools/list".equals(method)) {
                    return;
                }
            }
        }
    }

    private static void awaitFile(Path file) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.isRegularFile(file)) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Fixture child did not become ready.");
            }
            Thread.onSpinWait();
        }
    }

    private static String environmentDiagnostic(List<String> arguments) {
        String value = System.getenv(arguments.get(arguments.indexOf("--echo-env") + 1));
        return "length=%d,newline=%s".formatted(value.length(), value.contains("\n"));
    }
}
