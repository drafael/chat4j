package com.github.drafael.chat4j.stt.provider.whisper;

import java.nio.file.Path;
import java.util.List;

import static java.util.Collections.emptyList;

public record WhisperModelManagementSnapshot(
        Path modelRoot,
        Path tempRoot,
        List<WhisperModelCatalogEntry> catalog,
        List<WhisperInstalledModel> installedModels,
        List<WhisperLocalModelRow> rows,
        WhisperInstalledModel selectedModel,
        boolean runtimeReady,
        String statusMessage,
        boolean operationInProgress,
        String operationType,
        String operationModelId,
        String operationModelLabel,
        long bytesDownloaded,
        long totalBytes,
        boolean cancelable,
        boolean canceling,
        String operationStatus
) {

    public static WhisperModelManagementSnapshot empty(Path modelRoot, Path tempRoot) {
        return new WhisperModelManagementSnapshot(
                modelRoot,
                tempRoot,
                WhisperModelCatalog.entries(),
                emptyList(),
                emptyList(),
                null,
                true,
                "Download or select a Whisper.cpp model to enable transcription.",
                false,
                "",
                "",
                "",
                0,
                0,
                false,
                false,
                ""
        );
    }

    public boolean readyToTranscribe() {
        return runtimeReady && selectedModel != null && selectedModel.ready();
    }
}
