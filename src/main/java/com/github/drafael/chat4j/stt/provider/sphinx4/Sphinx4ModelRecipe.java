package com.github.drafael.chat4j.stt.provider.sphinx4;

import java.util.List;

import static java.util.Collections.emptyList;

public record Sphinx4ModelRecipe(
        String acousticModelPath,
        String dictionaryPath,
        String languageModelPath,
        List<String> requiredFiles,
        boolean generateUnigramLanguageModel
) {

    public Sphinx4ModelRecipe {
        requiredFiles = requiredFiles == null ? emptyList() : List.copyOf(requiredFiles);
    }
}
