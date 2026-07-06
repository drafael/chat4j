package com.github.drafael.chat4j.stt.provider.vosk;

import java.nio.file.Path;
import java.util.List;

import static java.util.Collections.emptyList;

public record VoskModelManagementSnapshot(
        Path modelRoot,
        Path tempRoot,
        List<VoskModelCatalogEntry> catalog,
        List<VoskInstalledModel> installedModels,
        List<VoskLocalModelRow> rows,
        VoskInstalledModel selectedModel,
        boolean runtimeReady,
        String statusMessage,
        boolean operationInProgress,
        String operationStatus
) {

    public static VoskModelManagementSnapshot empty(Path modelRoot, Path tempRoot) {
        return new VoskModelManagementSnapshot(
                modelRoot,
                tempRoot,
                emptyList(),
                emptyList(),
                emptyList(),
                null,
                true,
                "Download or add a Vosk model to enable transcription.",
                false,
                ""
        );
    }

    public boolean readyToTranscribe() {
        return runtimeReady && selectedModel != null && selectedModel.ready();
    }
}
