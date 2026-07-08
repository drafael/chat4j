package com.github.drafael.chat4j.stt.provider.sphinx4;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

import static java.util.Collections.emptyList;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Sphinx4ModelMetadata(
        int schemaVersion,
        String id,
        String label,
        String language,
        List<SourceArtifact> sourceArtifacts,
        String acousticModelPath,
        String dictionaryPath,
        String languageModelPath,
        int sampleRateHz,
        List<String> requiredFiles,
        String recipeId,
        int recipeVersion,
        boolean verifiedDownload
) {

    public static final String FILE_NAME = ".chat4j-sphinx4-model.json";

    public Sphinx4ModelMetadata {
        sourceArtifacts = sourceArtifacts == null ? emptyList() : List.copyOf(sourceArtifacts);
        requiredFiles = requiredFiles == null ? emptyList() : List.copyOf(requiredFiles);
    }

    public static Sphinx4ModelMetadata fromCatalog(Sphinx4ModelCatalogEntry entry) {
        Sphinx4ModelRecipe recipe = entry.recipe();
        return new Sphinx4ModelMetadata(
                1,
                entry.id(),
                entry.label(),
                entry.language(),
                entry.artifacts().stream()
                        .map(artifact -> new SourceArtifact(artifact.artifactId(), artifact.url(), artifact.sha256(), artifact.expectedSizeBytes()))
                        .toList(),
                recipe.acousticModelPath(),
                recipe.dictionaryPath(),
                recipe.languageModelPath(),
                entry.sampleRateHz(),
                recipe.requiredFiles(),
                entry.id(),
                1,
                entry.verifiedDownload()
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceArtifact(String artifactId, String url, String sha256, long sizeBytes) {
    }
}
