#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

status=0

check_import() {
  local type="$1"
  local import="$2"
  shift 2
  local package="${import%.*}"
  local class="${import##*.}"
  local escaped_package="${package//./\\.}"
  local pattern="^[[:space:]]*import[[:space:]]+${escaped_package}\\.(${class}|\\*)[[:space:]]*;"
  local file
  while IFS= read -r file; do
    local allowed=false
    local candidate
    for candidate in "$@"; do
      if [[ "$file" == "$candidate" ]]; then
        allowed=true
        break
      fi
    done
    if [[ "$allowed" != true ]]; then
      echo "Disallowed production ${type} import: ${file}" >&2
      status=1
    fi
  done < <(grep -ERIl --include='*.java' "$pattern" src/main/java || true)
}

check_import "ObjectMapper" "com.fasterxml.jackson.databind.ObjectMapper" \
  src/main/java/com/github/drafael/chat4j/json/JsonCodec.java \
  src/main/java/com/github/drafael/chat4j/mcp/McpClientSession.java \
  src/main/java/com/github/drafael/chat4j/mcp/McpConfigurationRepository.java \
  src/main/java/com/github/drafael/chat4j/persistence/catalog/CatalogJsonStructure.java \
  src/main/java/com/github/drafael/chat4j/persistence/conversation/ConversationMessageJsonCodec.java \
  src/main/java/com/github/drafael/chat4j/provider/support/ApiTokenVault.java \
  src/main/java/com/github/drafael/chat4j/provider/support/ProviderCapabilityJsonParser.java \
  src/main/java/com/github/drafael/chat4j/settings/McpJsonImporter.java

check_import "JsonNode" "com.fasterxml.jackson.databind.JsonNode" \
  src/main/java/com/github/drafael/chat4j/persistence/conversation/ConversationMessageJsonCodec.java \
  src/main/java/com/github/drafael/chat4j/provider/support/ProviderCapabilityJsonParser.java \
  src/main/java/com/github/drafael/chat4j/settings/McpJsonImporter.java

check_import "HttpClient" "java.net.http.HttpClient" \
  src/main/java/com/github/drafael/chat4j/http/JavaNetHttpTransport.java \
  src/main/java/com/github/drafael/chat4j/mcp/BoundedMcpHttpClientBuilder.java \
  src/main/java/com/github/drafael/chat4j/mcp/McpClientSession.java \
  src/main/java/com/github/drafael/chat4j/provider/capability/chat/impl/MistralSseTransport.java \
  src/main/java/com/github/drafael/chat4j/provider/support/LocalServiceHealth.java \
  src/main/java/com/github/drafael/chat4j/stt/provider/vosk/VoskModelInstaller.java \
  src/main/java/com/github/drafael/chat4j/stt/provider/whisper/WhisperModelInstaller.java

exit "$status"
