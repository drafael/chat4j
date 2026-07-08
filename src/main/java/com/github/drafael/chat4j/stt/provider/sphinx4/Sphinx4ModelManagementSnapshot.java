package com.github.drafael.chat4j.stt.provider.sphinx4;

import java.nio.file.Path;
import java.util.List;

import static java.util.Collections.emptyList;

public record Sphinx4ModelManagementSnapshot(
        Path modelRoot,
        Path tempRoot,
        List<Sphinx4ModelCatalogEntry> catalog,
        List<Sphinx4InstalledModel> installedModels,
        List<Sphinx4LocalModelRow> rows,
        Sphinx4InstalledModel selectedModel,
        boolean runtimeReady,
        String statusMessage,
        boolean operationInProgress,
        String operationStatus
) {

    public static Sphinx4ModelManagementSnapshot empty(Path modelRoot, Path tempRoot) {
        return new Sphinx4ModelManagementSnapshot(modelRoot, tempRoot, emptyList(), emptyList(), emptyList(), null, true, "Scanning Sphinx4 models...", false, "");
    }

    public boolean readyToTranscribe() {
        return runtimeReady && selectedModel != null && selectedModel.ready();
    }
}
