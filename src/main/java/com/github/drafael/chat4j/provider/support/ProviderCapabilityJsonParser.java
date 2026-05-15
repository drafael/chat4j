package com.github.drafael.chat4j.provider.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

import static com.github.drafael.chat4j.provider.support.ProviderCapabilityHints.*;
import static java.util.stream.Collectors.toSet;

final class ProviderCapabilityJsonParser {

    private ProviderCapabilityJsonParser() {
    }

    static Optional<JsonNode> resolveLmStudioModelNode(JsonNode root, String modelId) {
        JsonNode modelsNode = root.path("models");
        if (!modelsNode.isArray()) {
            return Optional.empty();
        }

        String normalizedModelId = normalize(modelId);
        return StreamSupport.stream(modelsNode.spliterator(), false)
                .filter(modelNode -> modelMatches(modelNode, normalizedModelId))
                .findFirst();
    }


    static Optional<Boolean> resolveImageSupportFromModelsList(JsonNode root, String modelId) {
        JsonNode dataNode = root.path("data");
        JsonNode modelsNode = dataNode.isArray() ? dataNode : root;
        if (!modelsNode.isArray()) {
            return Optional.empty();
        }

        String normalizedModelId = normalize(modelId);
        return StreamSupport.stream(modelsNode.spliterator(), false)
                .filter(modelNode -> modelMatches(modelNode, normalizedModelId))
                .map(ProviderCapabilityJsonParser::resolveImageSupportFromNode)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    static Optional<Boolean> resolveReasoningSupportFromModelsList(JsonNode root, String modelId) {
        JsonNode dataNode = root.path("data");
        JsonNode modelsNode = dataNode.isArray() ? dataNode : root;
        if (!modelsNode.isArray()) {
            return Optional.empty();
        }

        String normalizedModelId = normalize(modelId);
        return StreamSupport.stream(modelsNode.spliterator(), false)
                .filter(modelNode -> modelMatches(modelNode, normalizedModelId))
                .map(ProviderCapabilityJsonParser::resolveReasoningSupportFromNode)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    static Optional<Boolean> resolveToolSupportFromModelsList(JsonNode root, String modelId) {
        JsonNode dataNode = root.path("data");
        JsonNode modelsNode = dataNode.isArray() ? dataNode : root;
        if (!modelsNode.isArray()) {
            return Optional.empty();
        }

        String normalizedModelId = normalize(modelId);
        return StreamSupport.stream(modelsNode.spliterator(), false)
                .filter(modelNode -> modelMatches(modelNode, normalizedModelId))
                .map(ProviderCapabilityJsonParser::resolveToolSupportFromNode)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    static Optional<Boolean> resolveNativeWebSearchSupportFromModelsList(JsonNode root, String modelId) {
        JsonNode dataNode = root.path("data");
        JsonNode modelsNode = dataNode.isArray() ? dataNode : root;
        if (!modelsNode.isArray()) {
            return Optional.empty();
        }

        String normalizedModelId = normalize(modelId);
        return StreamSupport.stream(modelsNode.spliterator(), false)
                .filter(modelNode -> modelMatches(modelNode, normalizedModelId))
                .map(ProviderCapabilityJsonParser::resolveNativeWebSearchSupportFromNode)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    static Optional<Boolean> resolveImageSupportFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> directResolution = resolveImageSupportFromSingleNode(node);
        if (directResolution.isPresent()) {
            return directResolution;
        }

        Optional<Boolean> metaResolution = resolveImageSupportFromSingleNode(node.path("meta"));
        if (metaResolution.isPresent()) {
            return metaResolution;
        }

        Optional<Boolean> detailsResolution = resolveImageSupportFromSingleNode(node.path("details"));
        if (detailsResolution.isPresent()) {
            return detailsResolution;
        }

        return resolveImageSupportFromSingleNode(node.path("architecture"));
    }

    static Optional<Boolean> resolveReasoningSupportFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> directResolution = resolveReasoningSupportFromSingleNode(node);
        if (directResolution.isPresent()) {
            return directResolution;
        }

        Optional<Boolean> metaResolution = resolveReasoningSupportFromSingleNode(node.path("meta"));
        if (metaResolution.isPresent()) {
            return metaResolution;
        }

        Optional<Boolean> detailsResolution = resolveReasoningSupportFromSingleNode(node.path("details"));
        if (detailsResolution.isPresent()) {
            return detailsResolution;
        }

        return resolveReasoningSupportFromSingleNode(node.path("architecture"));
    }

    static Optional<Boolean> resolveToolSupportFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> directResolution = resolveToolSupportFromSingleNode(node);
        if (directResolution.isPresent()) {
            return directResolution;
        }

        Optional<Boolean> metaResolution = resolveToolSupportFromSingleNode(node.path("meta"));
        if (metaResolution.isPresent()) {
            return metaResolution;
        }

        Optional<Boolean> detailsResolution = resolveToolSupportFromSingleNode(node.path("details"));
        if (detailsResolution.isPresent()) {
            return detailsResolution;
        }

        return resolveToolSupportFromSingleNode(node.path("architecture"));
    }

    static Optional<Boolean> resolveNativeWebSearchSupportFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> directResolution = resolveNativeWebSearchSupportFromSingleNode(node);
        if (directResolution.isPresent()) {
            return directResolution;
        }

        Optional<Boolean> metaResolution = resolveNativeWebSearchSupportFromSingleNode(node.path("meta"));
        if (metaResolution.isPresent()) {
            return metaResolution;
        }

        Optional<Boolean> detailsResolution = resolveNativeWebSearchSupportFromSingleNode(node.path("details"));
        if (detailsResolution.isPresent()) {
            return detailsResolution;
        }

        return resolveNativeWebSearchSupportFromSingleNode(node.path("architecture"));
    }

    private static Optional<Boolean> resolveImageSupportFromSingleNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> booleanResolution = resolveImageSupportFromBooleanFields(node);
        if (booleanResolution.isPresent()) {
            return booleanResolution;
        }

        Optional<Boolean> modalitiesResolution = resolveImageSupportFromModalities(node);
        if (modalitiesResolution.isPresent()) {
            return modalitiesResolution;
        }

        Optional<Boolean> modalityTextResolution = resolveImageSupportFromModalityText(node.path("modality"));
        if (modalityTextResolution.isPresent()) {
            return modalityTextResolution;
        }

        Optional<Boolean> capabilitiesResolution = resolveImageSupportFromCapabilitiesField(node.path("capabilities"));
        if (capabilitiesResolution.isPresent()) {
            return capabilitiesResolution;
        }

        Optional<Boolean> architectureResolution = resolveImageSupportFromSingleNode(node.path("architecture"));
        if (architectureResolution.isPresent()) {
            return architectureResolution;
        }

        if (containsOllamaVisionSignals(node)) {
            return Optional.of(true);
        }

        return Optional.empty();
    }

    private static Optional<Boolean> resolveReasoningSupportFromSingleNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> booleanResolution = resolveReasoningSupportFromBooleanFields(node);
        if (booleanResolution.isPresent()) {
            return booleanResolution;
        }

        Optional<Boolean> capabilitiesResolution = resolveReasoningSupportFromCapabilitiesField(node.path("capabilities"));
        if (capabilitiesResolution.isPresent()) {
            return capabilitiesResolution;
        }

        Optional<Boolean> supportedParametersResolution = resolveReasoningSupportFromSupportedParameters(
                node.path("supported_parameters"));
        if (supportedParametersResolution.isPresent()) {
            return supportedParametersResolution;
        }

        Optional<Boolean> tagsResolution = resolveReasoningSupportFromStringArray(node.path("tags"));
        if (tagsResolution.isPresent()) {
            return tagsResolution;
        }

        Optional<Boolean> featuresResolution = resolveReasoningSupportFromStringArray(node.path("features"));
        if (featuresResolution.isPresent()) {
            return featuresResolution;
        }

        Optional<Boolean> inputModalitiesResolution = resolveReasoningSupportFromStringArray(node.path("input_modalities"));
        if (inputModalitiesResolution.isPresent()) {
            return inputModalitiesResolution;
        }

        Optional<Boolean> inputModalitiesCamelResolution = resolveReasoningSupportFromStringArray(node.path("inputModalities"));
        if (inputModalitiesCamelResolution.isPresent()) {
            return inputModalitiesCamelResolution;
        }

        Optional<Boolean> modalitiesResolution = resolveReasoningSupportFromStringArray(node.path("modalities"));
        if (modalitiesResolution.isPresent()) {
            return modalitiesResolution;
        }

        Optional<Boolean> generationMethodsResolution = resolveReasoningSupportFromStringArray(
                node.path("supportedGenerationMethods"));
        if (generationMethodsResolution.isPresent()) {
            return generationMethodsResolution;
        }

        Optional<Boolean> architectureResolution = resolveReasoningSupportFromSingleNode(node.path("architecture"));
        if (architectureResolution.isPresent()) {
            return architectureResolution;
        }

        return resolveReasoningSupportFromStringArray(node.path("supported_generation_methods"));
    }

    private static Optional<Boolean> resolveToolSupportFromSingleNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> booleanResolution = resolveToolSupportFromBooleanFields(node);
        if (booleanResolution.isPresent()) {
            return booleanResolution;
        }

        Optional<Boolean> capabilitiesResolution = resolveToolSupportFromCapabilitiesField(node.path("capabilities"));
        if (capabilitiesResolution.isPresent()) {
            return capabilitiesResolution;
        }

        Optional<Boolean> directFieldsResolution = resolveToolSupportFromKnownFields(node);
        if (directFieldsResolution.isPresent()) {
            return directFieldsResolution;
        }

        Optional<Boolean> supportedParametersResolution = resolveToolSupportFromSupportedParameters(
                node.path("supported_parameters"));
        if (supportedParametersResolution.isPresent()) {
            return supportedParametersResolution;
        }

        Optional<Boolean> supportedParametersCamelResolution = resolveToolSupportFromSupportedParameters(
                node.path("supportedParameters"));
        if (supportedParametersCamelResolution.isPresent()) {
            return supportedParametersCamelResolution;
        }

        Optional<Boolean> tagsResolution = resolveToolSupportFromStringArray(node.path("tags"));
        if (tagsResolution.isPresent()) {
            return tagsResolution;
        }

        Optional<Boolean> featuresResolution = resolveToolSupportFromStringArray(node.path("features"));
        if (featuresResolution.isPresent()) {
            return featuresResolution;
        }

        return resolveToolSupportFromSingleNode(node.path("architecture"));
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromSingleNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }

        Optional<Boolean> booleanResolution = resolveNativeWebSearchSupportFromBooleanFields(node);
        if (booleanResolution.isPresent()) {
            return booleanResolution;
        }

        Optional<Boolean> capabilitiesResolution = resolveNativeWebSearchSupportFromCapabilitiesField(
                node.path("capabilities")
        );
        if (capabilitiesResolution.isPresent()) {
            return capabilitiesResolution;
        }

        Optional<Boolean> directFieldsResolution = resolveNativeWebSearchSupportFromKnownFields(node);
        if (directFieldsResolution.isPresent()) {
            return directFieldsResolution;
        }

        Optional<Boolean> supportedParametersResolution = resolveNativeWebSearchSupportFromSupportedParameters(
                node.path("supported_parameters")
        );
        if (supportedParametersResolution.isPresent()) {
            return supportedParametersResolution;
        }

        Optional<Boolean> supportedParametersCamelResolution = resolveNativeWebSearchSupportFromSupportedParameters(
                node.path("supportedParameters")
        );
        if (supportedParametersCamelResolution.isPresent()) {
            return supportedParametersCamelResolution;
        }

        Optional<Boolean> tagsResolution = resolveNativeWebSearchSupportFromStringArray(node.path("tags"));
        if (tagsResolution.isPresent()) {
            return tagsResolution;
        }

        Optional<Boolean> featuresResolution = resolveNativeWebSearchSupportFromStringArray(node.path("features"));
        if (featuresResolution.isPresent()) {
            return featuresResolution;
        }

        return resolveNativeWebSearchSupportFromSingleNode(node.path("architecture"));
    }

    private static Optional<Boolean> resolveImageSupportFromBooleanFields(JsonNode node) {
        return CAPABILITY_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .reduce((left, right) -> left || right)
                .map(Optional::of)
                .orElse(Optional.empty());
    }

    private static Optional<Boolean> resolveReasoningSupportFromBooleanFields(JsonNode node) {
        boolean hasReasoningField = REASONING_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .anyMatch(Optional::isPresent);
        if (!hasReasoningField) {
            return Optional.empty();
        }

        boolean supported = REASONING_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .reduce((left, right) -> left || right)
                .orElse(false);

        return Optional.of(supported);
    }

    private static Optional<Boolean> resolveToolSupportFromBooleanFields(JsonNode node) {
        boolean hasToolField = TOOL_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .anyMatch(Optional::isPresent);
        if (!hasToolField) {
            return Optional.empty();
        }

        boolean supported = TOOL_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .reduce((left, right) -> left || right)
                .orElse(false);

        return Optional.of(supported);
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromBooleanFields(JsonNode node) {
        boolean hasNativeWebSearchField = NATIVE_WEB_SEARCH_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .anyMatch(Optional::isPresent);
        if (!hasNativeWebSearchField) {
            return Optional.empty();
        }

        boolean supported = NATIVE_WEB_SEARCH_BOOLEAN_FIELDS.stream()
                .map(field -> booleanField(node, field))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .reduce((left, right) -> left || right)
                .orElse(false);

        return Optional.of(supported);
    }

    private static Optional<Boolean> resolveImageSupportFromModalities(JsonNode node) {
        Optional<Boolean> inputModalities = resolveImageSupportFromStringArray(node.path("input_modalities"));
        if (inputModalities.isPresent()) {
            return inputModalities;
        }

        Optional<Boolean> inputModalitiesCamel = resolveImageSupportFromStringArray(node.path("inputModalities"));
        if (inputModalitiesCamel.isPresent()) {
            return inputModalitiesCamel;
        }

        Optional<Boolean> supportedInputModalities = resolveImageSupportFromStringArray(node.path("supportedInputModalities"));
        if (supportedInputModalities.isPresent()) {
            return supportedInputModalities;
        }

        Optional<Boolean> supportedInputModalitiesSnake = resolveImageSupportFromStringArray(node.path("supported_input_modalities"));
        if (supportedInputModalitiesSnake.isPresent()) {
            return supportedInputModalitiesSnake;
        }

        Optional<Boolean> modalities = resolveImageSupportFromStringArray(node.path("modalities"));
        if (modalities.isPresent()) {
            return modalities;
        }

        Optional<Boolean> supportedModalities = resolveImageSupportFromStringArray(node.path("supportedModalities"));
        if (supportedModalities.isPresent()) {
            return supportedModalities;
        }

        return resolveImageSupportFromStringArray(node.path("supported_modalities"));
    }

    static Optional<Boolean> resolveImageSupportFromCapabilitiesField(JsonNode capabilitiesNode) {
        if (capabilitiesNode == null || capabilitiesNode.isMissingNode() || capabilitiesNode.isNull()) {
            return Optional.empty();
        }

        if (capabilitiesNode.isArray()) {
            return resolveImageSupportFromStringArray(capabilitiesNode);
        }

        if (capabilitiesNode.isObject()) {
            Optional<Boolean> objectResolution = resolveImageSupportFromSingleNode(capabilitiesNode);
            if (objectResolution.isPresent()) {
                return objectResolution;
            }

            Optional<Boolean> modalitiesResolution = resolveImageSupportFromStringArray(capabilitiesNode.path("modalities"));
            if (modalitiesResolution.isPresent()) {
                return modalitiesResolution;
            }

            return resolveImageSupportFromModalityText(capabilitiesNode.path("modality"));
        }

        return Optional.empty();
    }

    private static Optional<Boolean> resolveReasoningSupportFromCapabilitiesField(JsonNode capabilitiesNode) {
        if (capabilitiesNode == null || capabilitiesNode.isMissingNode() || capabilitiesNode.isNull()) {
            return Optional.empty();
        }

        if (capabilitiesNode.isArray()) {
            return resolveReasoningSupportFromStringArray(capabilitiesNode);
        }

        if (capabilitiesNode.isObject()) {
            Optional<Boolean> booleanResolution = resolveReasoningSupportFromBooleanFields(capabilitiesNode);
            if (booleanResolution.isPresent()) {
                return booleanResolution;
            }

            Optional<Boolean> modalitiesResolution = resolveReasoningSupportFromStringArray(capabilitiesNode.path("modalities"));
            if (modalitiesResolution.isPresent()) {
                return modalitiesResolution;
            }

            Optional<Boolean> tagsResolution = resolveReasoningSupportFromStringArray(capabilitiesNode.path("tags"));
            if (tagsResolution.isPresent()) {
                return tagsResolution;
            }

            return resolveReasoningSupportFromSupportedParameters(capabilitiesNode.path("supported_parameters"));
        }

        return Optional.empty();
    }

    private static Optional<Boolean> resolveToolSupportFromCapabilitiesField(JsonNode capabilitiesNode) {
        if (capabilitiesNode == null || capabilitiesNode.isMissingNode() || capabilitiesNode.isNull()) {
            return Optional.empty();
        }

        if (capabilitiesNode.isArray()) {
            return resolveToolSupportFromStringArray(capabilitiesNode);
        }

        if (capabilitiesNode.isObject()) {
            Optional<Boolean> booleanResolution = resolveToolSupportFromBooleanFields(capabilitiesNode);
            if (booleanResolution.isPresent()) {
                return booleanResolution;
            }

            Optional<Boolean> directFieldsResolution = resolveToolSupportFromKnownFields(capabilitiesNode);
            if (directFieldsResolution.isPresent()) {
                return directFieldsResolution;
            }

            Optional<Boolean> tagsResolution = resolveToolSupportFromStringArray(capabilitiesNode.path("tags"));
            if (tagsResolution.isPresent()) {
                return tagsResolution;
            }

            Optional<Boolean> featuresResolution = resolveToolSupportFromStringArray(capabilitiesNode.path("features"));
            if (featuresResolution.isPresent()) {
                return featuresResolution;
            }

            Optional<Boolean> supportedParametersResolution = resolveToolSupportFromSupportedParameters(
                    capabilitiesNode.path("supported_parameters"));
            if (supportedParametersResolution.isPresent()) {
                return supportedParametersResolution;
            }

            return resolveToolSupportFromSupportedParameters(capabilitiesNode.path("supportedParameters"));
        }

        return resolveToolSupportFromFieldValue(capabilitiesNode);
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromCapabilitiesField(JsonNode capabilitiesNode) {
        if (capabilitiesNode == null || capabilitiesNode.isMissingNode() || capabilitiesNode.isNull()) {
            return Optional.empty();
        }

        if (capabilitiesNode.isArray()) {
            return resolveNativeWebSearchSupportFromStringArray(capabilitiesNode);
        }

        if (capabilitiesNode.isObject()) {
            Optional<Boolean> booleanResolution = resolveNativeWebSearchSupportFromBooleanFields(capabilitiesNode);
            if (booleanResolution.isPresent()) {
                return booleanResolution;
            }

            Optional<Boolean> directFieldsResolution = resolveNativeWebSearchSupportFromKnownFields(capabilitiesNode);
            if (directFieldsResolution.isPresent()) {
                return directFieldsResolution;
            }

            Optional<Boolean> tagsResolution = resolveNativeWebSearchSupportFromStringArray(capabilitiesNode.path("tags"));
            if (tagsResolution.isPresent()) {
                return tagsResolution;
            }

            Optional<Boolean> featuresResolution = resolveNativeWebSearchSupportFromStringArray(capabilitiesNode.path("features"));
            if (featuresResolution.isPresent()) {
                return featuresResolution;
            }

            Optional<Boolean> supportedParametersResolution = resolveNativeWebSearchSupportFromSupportedParameters(
                    capabilitiesNode.path("supported_parameters")
            );
            if (supportedParametersResolution.isPresent()) {
                return supportedParametersResolution;
            }

            return resolveNativeWebSearchSupportFromSupportedParameters(capabilitiesNode.path("supportedParameters"));
        }

        return resolveNativeWebSearchSupportFromFieldValue(capabilitiesNode);
    }

    private static Optional<Boolean> resolveImageSupportFromStringArray(JsonNode arrayNode) {
        return resolveSupportFromStringArray(arrayNode, DYNAMIC_IMAGE_HINTS, DYNAMIC_TEXT_ONLY_HINTS);
    }

    private static Optional<Boolean> resolveReasoningSupportFromStringArray(JsonNode arrayNode) {
        return resolveSupportFromStringArray(arrayNode, DYNAMIC_REASONING_HINTS, DYNAMIC_NON_REASONING_HINTS);
    }

    private static Optional<Boolean> resolveToolSupportFromStringArray(JsonNode arrayNode) {
        return resolveSupportFromStringArray(arrayNode, DYNAMIC_TOOL_HINTS, DYNAMIC_NON_TOOL_HINTS);
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromStringArray(JsonNode arrayNode) {
        return resolveSupportFromStringArray(
                arrayNode,
                DYNAMIC_NATIVE_WEB_SEARCH_HINTS,
                DYNAMIC_NON_NATIVE_WEB_SEARCH_HINTS
        );
    }

    private static Optional<Boolean> resolveImageSupportFromModalityText(JsonNode modalityNode) {
        if (!modalityNode.isTextual()) {
            return Optional.empty();
        }

        String modality = normalize(modalityNode.asText(""));
        if (modality.isBlank()) {
            return Optional.empty();
        }

        if (containsAny(modality, DYNAMIC_IMAGE_HINTS)) {
            return Optional.of(true);
        }

        if (containsAny(modality, DYNAMIC_TEXT_ONLY_HINTS)) {
            return Optional.of(false);
        }

        return Optional.empty();
    }

    private static Optional<Boolean> resolveReasoningSupportFromSupportedParameters(JsonNode parametersNode) {
        return resolveReasoningSupportFromStringArray(parametersNode);
    }

    private static Optional<Boolean> resolveToolSupportFromSupportedParameters(JsonNode parametersNode) {
        return resolveToolSupportFromStringArray(parametersNode);
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromSupportedParameters(JsonNode parametersNode) {
        return resolveNativeWebSearchSupportFromStringArray(parametersNode);
    }

    private static Optional<Boolean> resolveToolSupportFromKnownFields(JsonNode node) {
        return StreamSupport.stream(List.of(
                        node.path("tools"),
                        node.path("tool_use"),
                        node.path("toolUse"),
                        node.path("function_calling"),
                        node.path("functionCalling"),
                        node.path("tool_calling"),
                        node.path("toolCalling")
                ).spliterator(), false)
                .map(ProviderCapabilityJsonParser::resolveToolSupportFromFieldValue)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromKnownFields(JsonNode node) {
        return StreamSupport.stream(List.of(
                        node.path("web_search"),
                        node.path("webSearch"),
                        node.path("native_web_search"),
                        node.path("nativeWebSearch"),
                        node.path("web_browsing"),
                        node.path("webBrowsing"),
                        node.path("grounding"),
                        node.path("google_search"),
                        node.path("googleSearch")
                ).spliterator(), false)
                .map(ProviderCapabilityJsonParser::resolveNativeWebSearchSupportFromFieldValue)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static Optional<Boolean> resolveToolSupportFromFieldValue(JsonNode fieldNode) {
        if (fieldNode == null || fieldNode.isMissingNode() || fieldNode.isNull()) {
            return Optional.empty();
        }

        if (fieldNode.isBoolean()) {
            return Optional.of(fieldNode.asBoolean());
        }

        if (fieldNode.isTextual()) {
            String normalized = normalize(fieldNode.asText(""));
            if (normalized.isBlank()) {
                return Optional.empty();
            }

            if (containsAny(normalized, DYNAMIC_TOOL_HINTS)
                    || "enabled".equals(normalized)
                    || "supported".equals(normalized)
                    || "required".equals(normalized)
            ) {
                return Optional.of(true);
            }

            if (containsAny(normalized, DYNAMIC_NON_TOOL_HINTS)
                    || "disabled".equals(normalized)
                    || "unsupported".equals(normalized)
            ) {
                return Optional.of(false);
            }

            return Optional.empty();
        }

        if (fieldNode.isArray()) {
            if (fieldNode.isEmpty()) {
                return Optional.of(false);
            }

            Optional<Boolean> textualResolution = resolveToolSupportFromStringArray(fieldNode);
            if (textualResolution.isPresent()) {
                return textualResolution;
            }

            return Optional.of(true);
        }

        if (fieldNode.isObject()) {
            Optional<Boolean> enabledResolution = booleanField(fieldNode, "enabled");
            if (enabledResolution.isPresent()) {
                return enabledResolution;
            }

            Optional<Boolean> supportedResolution = booleanField(fieldNode, "supported");
            if (supportedResolution.isPresent()) {
                return supportedResolution;
            }

            Optional<Boolean> allowResolution = booleanField(fieldNode, "allow");
            if (allowResolution.isPresent()) {
                return allowResolution;
            }

            return fieldNode.fieldNames().hasNext()
                    ? Optional.of(true)
                    : Optional.of(false);
        }

        return Optional.empty();
    }

    private static Optional<Boolean> resolveNativeWebSearchSupportFromFieldValue(JsonNode fieldNode) {
        if (fieldNode == null || fieldNode.isMissingNode() || fieldNode.isNull()) {
            return Optional.empty();
        }

        if (fieldNode.isBoolean()) {
            return Optional.of(fieldNode.asBoolean());
        }

        if (fieldNode.isTextual()) {
            String normalized = normalize(fieldNode.asText(""));
            if (normalized.isBlank()) {
                return Optional.empty();
            }

            if (containsAny(normalized, DYNAMIC_NATIVE_WEB_SEARCH_HINTS)
                    || "enabled".equals(normalized)
                    || "supported".equals(normalized)
                    || "required".equals(normalized)
            ) {
                return Optional.of(true);
            }

            if (containsAny(normalized, DYNAMIC_NON_NATIVE_WEB_SEARCH_HINTS)
                    || "disabled".equals(normalized)
                    || "unsupported".equals(normalized)
            ) {
                return Optional.of(false);
            }

            return Optional.empty();
        }

        if (fieldNode.isArray()) {
            if (fieldNode.isEmpty()) {
                return Optional.of(false);
            }

            Optional<Boolean> textualResolution = resolveNativeWebSearchSupportFromStringArray(fieldNode);
            if (textualResolution.isPresent()) {
                return textualResolution;
            }

            return Optional.of(true);
        }

        if (fieldNode.isObject()) {
            Optional<Boolean> enabledResolution = booleanField(fieldNode, "enabled");
            if (enabledResolution.isPresent()) {
                return enabledResolution;
            }

            Optional<Boolean> supportedResolution = booleanField(fieldNode, "supported");
            if (supportedResolution.isPresent()) {
                return supportedResolution;
            }

            Optional<Boolean> allowResolution = booleanField(fieldNode, "allow");
            if (allowResolution.isPresent()) {
                return allowResolution;
            }

            return fieldNode.fieldNames().hasNext()
                    ? Optional.of(true)
                    : Optional.of(false);
        }

        return Optional.empty();
    }

    private static Optional<Boolean> booleanField(JsonNode node, String expectedFieldName) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isObject()) {
            return Optional.empty();
        }

        String normalizedExpectedFieldName = normalizeFieldName(expectedFieldName);
        for (Iterator<Map.Entry<String, JsonNode>> fields = node.fields(); fields.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!normalizeFieldName(entry.getKey()).equals(normalizedExpectedFieldName)) {
                continue;
            }

            if (entry.getValue().isBoolean()) {
                return Optional.of(entry.getValue().asBoolean());
            }
        }

        return Optional.empty();
    }

    private static String normalizeFieldName(String fieldName) {
        return normalize(fieldName).replace("_", "").replace("-", "");
    }

    private static Optional<Boolean> resolveSupportFromStringArray(
            JsonNode arrayNode,
            Set<String> positiveHints,
            Set<String> negativeHints
    ) {
        if (!arrayNode.isArray()) {
            return Optional.empty();
        }

        Set<String> values = StreamSupport.stream(arrayNode.spliterator(), false)
                .map(node -> normalize(node.asText("")))
                .filter(value -> !value.isBlank())
                .collect(toSet());

        if (values.isEmpty()) {
            return Optional.empty();
        }

        boolean hasPositiveHint = values.stream().anyMatch(value -> containsAny(value, positiveHints));
        if (hasPositiveHint) {
            return Optional.of(true);
        }

        boolean explicitNegative = values.stream().allMatch(value -> containsAny(value, negativeHints));
        if (explicitNegative) {
            return Optional.of(false);
        }

        return Optional.empty();
    }

    static boolean containsOllamaVisionSignals(JsonNode root) {
        JsonNode projectorInfo = root.path("projector_info");
        if (projectorInfo.isObject() && projectorInfo.fieldNames().hasNext()) {
            return true;
        }

        String modelfile = normalize(root.path("modelfile").asText(""));
        if (containsAny(modelfile, OLLAMA_MODELINFO_FIELD_VISION_HINTS)) {
            return true;
        }

        JsonNode modelInfo = root.path("model_info");
        if (modelInfo.isObject()) {
            for (var iterator = modelInfo.fields(); iterator.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                if (containsAny(normalize(entry.getKey()), OLLAMA_MODELINFO_FIELD_VISION_HINTS)) {
                    return true;
                }

                if (entry.getValue().isTextual()
                        && containsAny(normalize(entry.getValue().asText("")), OLLAMA_MODELINFO_TEXT_VISION_HINTS)
                ) {
                    return true;
                }
            }
        }

        JsonNode familiesNode = root.path("details").path("families");
        if (familiesNode.isArray()) {
            return StreamSupport.stream(familiesNode.spliterator(), false)
                    .map(node -> normalize(node.asText("")))
                    .anyMatch(family -> containsAny(family, OLLAMA_MODELFAMILY_VISION_HINTS));
        }

        return false;
    }

    static boolean modelMatches(JsonNode modelNode, String normalizedModelId) {
        if (StringUtils.isBlank(normalizedModelId)) {
            return false;
        }

        List<String> candidates = List.of(
                normalize(modelNode.path("id").asText("")),
                normalize(modelNode.path("name").asText("")),
                normalize(modelNode.path("key").asText("")),
                normalize(modelNode.path("display_name").asText("")),
                normalize(modelNode.path("displayName").asText("")),
                normalize(modelNode.path("canonical_slug").asText("")),
                normalize(modelNode.path("baseModelId").asText("")),
                normalize(modelNode.path("base_model_id").asText(""))
        );

        return candidates.stream()
                .filter(candidate -> !candidate.isBlank())
                .anyMatch(candidate -> candidate.equals(normalizedModelId)
                        || candidate.endsWith("/%s".formatted(normalizedModelId))
                        || normalizedModelId.endsWith("/%s".formatted(candidate)));
    }

}
