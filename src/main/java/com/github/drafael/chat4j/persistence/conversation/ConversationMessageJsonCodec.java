package com.github.drafael.chat4j.persistence.conversation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.drafael.chat4j.provider.api.Message;
import com.github.drafael.chat4j.provider.api.Role;
import com.github.drafael.chat4j.provider.api.content.AgentToolActivityMeta;
import com.github.drafael.chat4j.provider.api.content.AttachmentRef;
import com.github.drafael.chat4j.provider.api.content.ContentPart;
import com.github.drafael.chat4j.provider.api.content.ContentParts;
import com.github.drafael.chat4j.provider.api.content.FilePart;
import com.github.drafael.chat4j.provider.api.content.GeneratedImagePart;
import com.github.drafael.chat4j.provider.api.content.ImagePart;
import com.github.drafael.chat4j.provider.api.content.MessageMeta;
import com.github.drafael.chat4j.provider.api.content.TextPart;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;

import static java.util.Collections.emptyList;

class ConversationMessageJsonCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    String serializeParts(List<ContentPart> parts) {
        ArrayNode root = JSON.createArrayNode();
        if (parts == null) {
            return root.toString();
        }

        parts.stream()
                .filter(Objects::nonNull)
                .map(this::serializePart)
                .forEach(root::add);

        return root.toString();
    }

    String serializeMeta(MessageMeta meta) {
        MessageMeta value = meta == null ? MessageMeta.empty() : meta;
        ObjectNode node = JSON.createObjectNode();

        ArrayNode activeSkills = JSON.createArrayNode();
        value.activeSkills().forEach(activeSkills::add);
        node.set("activeSkills", activeSkills);

        ArrayNode fallbackNotices = JSON.createArrayNode();
        value.fallbackNotices().forEach(fallbackNotices::add);
        node.set("fallbackNotices", fallbackNotices);

        node.put("cancelled", value.cancelled());
        node.put("error", value.error());
        node.put("assistantThinking", value.assistantThinking());
        node.put("assistantWebSearch", value.assistantWebSearch());

        ArrayNode agentToolActivities = JSON.createArrayNode();
        value.agentToolActivities().stream()
                .map(this::serializeAgentToolActivity)
                .forEach(agentToolActivities::add);
        node.set("agentToolActivities", agentToolActivities);
        return node.toString();
    }

    Message deserializeMessage(String role, String content, String contentJson, String metaJson, LocalDateTime createdAt) {
        List<ContentPart> parts = deserializeParts(contentJson, content);
        MessageMeta meta = deserializeMeta(metaJson);
        Instant timestamp = createdAt.atZone(ZoneId.systemDefault()).toInstant();
        return new Message(parseRole(role), parts, timestamp, meta);
    }

    Role parseRole(String role) {
        if (StringUtils.isBlank(role)) {
            return Role.USER;
        }

        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException e) {
            return Role.USER;
        }
    }

    private ObjectNode serializePart(ContentPart part) {
        ObjectNode node = JSON.createObjectNode();

        if (part instanceof TextPart textPart) {
            node.put("type", "text");
            node.put("text", textPart.text());
            return node;
        }

        if (part instanceof ImagePart imagePart) {
            node.put("type", "image");
            node.set("attachment", serializeAttachment(imagePart.attachmentRef()));
            if (imagePart.width() != null) {
                node.put("width", imagePart.width());
            }
            if (imagePart.height() != null) {
                node.put("height", imagePart.height());
            }
            return node;
        }

        if (part instanceof FilePart filePart) {
            node.put("type", "file");
            node.set("attachment", serializeAttachment(filePart.attachmentRef()));
            return node;
        }

        if (part instanceof GeneratedImagePart generatedImagePart) {
            node.put("type", "generated_image");
            node.set("attachment", serializeAttachment(generatedImagePart.attachmentRef()));
            if (generatedImagePart.width() != null) {
                node.put("width", generatedImagePart.width());
            }
            if (generatedImagePart.height() != null) {
                node.put("height", generatedImagePart.height());
            }
            node.put("altText", generatedImagePart.altText());
            return node;
        }

        node.put("type", "text");
        node.put("text", part.asTextProjection());
        return node;
    }

    private ObjectNode serializeAttachment(AttachmentRef attachmentRef) {
        ObjectNode node = JSON.createObjectNode();
        AttachmentRef value = attachmentRef == null
                ? new AttachmentRef(null, null, null, null, 0L, null)
                : attachmentRef;

        if (value.id() == null) {
            node.putNull("id");
        } else {
            node.put("id", value.id().toString());
        }

        node.put("storagePath", value.storagePath());
        node.put("originalName", value.originalName());
        node.put("mimeType", value.mimeType());
        node.put("sizeBytes", value.sizeBytes());
        node.put("sha256", value.sha256());
        return node;
    }

    private ObjectNode serializeAgentToolActivity(AgentToolActivityMeta activity) {
        AgentToolActivityMeta value = activity == null
                ? new AgentToolActivityMeta("", "unknown", "STARTED", "", "")
                : activity;
        ObjectNode node = JSON.createObjectNode();
        node.put("invocationId", value.invocationId());
        node.put("toolName", value.toolName());
        node.put("status", value.status());
        node.put("argumentsSummary", value.argumentsSummary());
        node.put("message", value.message());
        return node;
    }

    private List<ContentPart> deserializeParts(String contentJson, String fallbackContent) {
        if (StringUtils.isBlank(contentJson)) {
            return ContentParts.ofText(fallbackContent);
        }

        try {
            JsonNode root = JSON.readTree(contentJson);
            if (!root.isArray()) {
                return ContentParts.ofText(fallbackContent);
            }

            List<ContentPart> parts = new ArrayList<>();
            root.forEach(node -> deserializePart(node).ifPresent(parts::add));
            if (parts.isEmpty()) {
                return ContentParts.ofText(fallbackContent);
            }
            return List.copyOf(parts);
        } catch (Exception e) {
            return ContentParts.ofText(fallbackContent);
        }
    }

    private Optional<ContentPart> deserializePart(JsonNode node) {
        String type = node.path("type").asText("");
        return switch (type) {
            case "text" -> Optional.of(new TextPart(node.path("text").asText("")));
            case "image" -> Optional.of(new ImagePart(
                    deserializeAttachment(node.path("attachment")),
                    readOptionalInt(node, "width"),
                    readOptionalInt(node, "height")
            ));
            case "file" -> Optional.of(new FilePart(deserializeAttachment(node.path("attachment"))));
            case "generated_image" -> Optional.of(new GeneratedImagePart(
                    deserializeAttachment(node.path("attachment")),
                    readOptionalInt(node, "width"),
                    readOptionalInt(node, "height"),
                    node.path("altText").asText("Generated image")
            ));
            default -> Optional.empty();
        };
    }

    private MessageMeta deserializeMeta(String metaJson) {
        if (StringUtils.isBlank(metaJson)) {
            return MessageMeta.empty();
        }

        try {
            JsonNode node = JSON.readTree(metaJson);
            List<String> activeSkills = new ArrayList<>();
            JsonNode skillsNode = node.path("activeSkills");
            if (skillsNode.isArray()) {
                skillsNode.forEach(skillNode -> {
                    String skill = skillNode.asText("").trim();
                    if (!skill.isBlank()) {
                        activeSkills.add(skill);
                    }
                });
            }

            List<String> fallbackNotices = new ArrayList<>();
            JsonNode noticesNode = node.path("fallbackNotices");
            if (noticesNode.isArray()) {
                noticesNode.forEach(noticeNode -> {
                    String notice = noticeNode.asText("").trim();
                    if (!notice.isBlank()) {
                        fallbackNotices.add(notice);
                    }
                });
            }

            return new MessageMeta(
                    activeSkills,
                    fallbackNotices,
                    node.path("cancelled").asBoolean(false),
                    node.path("error").asText(""),
                    node.path("assistantThinking").asText(""),
                    node.path("assistantWebSearch").asText(""),
                    deserializeAgentToolActivities(node.path("agentToolActivities"))
            );
        } catch (Exception e) {
            return MessageMeta.empty();
        }
    }

    private List<AgentToolActivityMeta> deserializeAgentToolActivities(JsonNode node) {
        if (node == null || !node.isArray()) {
            return emptyList();
        }

        List<AgentToolActivityMeta> activities = new ArrayList<>();
        node.forEach(activityNode -> {
            if (activityNode == null || !activityNode.isObject()) {
                return;
            }

            activities.add(new AgentToolActivityMeta(
                    activityNode.path("invocationId").asText(""),
                    activityNode.path("toolName").asText("unknown"),
                    activityNode.path("status").asText("STARTED"),
                    activityNode.path("argumentsSummary").asText(""),
                    activityNode.path("message").asText("")
            ));
        });
        return List.copyOf(activities);
    }

    private AttachmentRef deserializeAttachment(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return new AttachmentRef(null, null, null, null, 0L, null);
        }

        return new AttachmentRef(
                parseUuid(node.path("id").asText(null)),
                node.path("storagePath").asText(""),
                node.path("originalName").asText(""),
                node.path("mimeType").asText(""),
                node.path("sizeBytes").asLong(0L),
                node.path("sha256").asText("")
        );
    }

    private UUID parseUuid(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Integer readOptionalInt(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asInt();
    }
}
