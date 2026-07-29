package com.github.drafael.chat4j.chat.agent;

import com.github.drafael.chat4j.mcp.McpDiscoveredTool;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentToolSchemaTest {

    @Test
    @DisplayName("Tool schemas preserve null values while deeply isolating mutable input")
    void constructor_whenSchemaIsNested_preservesNullsAndDeeplyFreezesValues() {
        List<Object> enumValues = new ArrayList<>(List.of("one"));
        enumValues.add(null);
        List<Object> types = new ArrayList<>();
        types.add("string");
        types.add(null);
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", types);
        property.put("enum", enumValues);
        property.put("default", null);
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("value", property);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        var subject = new AgentToolDefinition("tool", "description", schema, AgentToolSource.MCP);
        var discovered = new McpDiscoveredTool("tool", "", "", schema, schema);

        property.put("default", "changed");
        enumValues.set(0, "changed");

        Map<String, Object> immutableProperty = (Map<String, Object>) ((Map<?, ?>) subject.inputSchema()
                .get("properties")).get("value");
        List<Object> immutableEnum = (List<Object>) immutableProperty.get("enum");
        assertThat(immutableProperty).containsEntry("default", null);
        assertThat(immutableEnum).containsExactly("one", null);
        assertThat(discovered.inputSchema()).isEqualTo(subject.inputSchema());
        assertThatThrownBy(() -> immutableProperty.put("extra", true))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) immutableProperty.get("enum")).add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
