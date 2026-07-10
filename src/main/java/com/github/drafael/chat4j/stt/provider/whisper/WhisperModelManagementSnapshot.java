package com.github.drafael.chat4j.stt.provider.whisper;

import java.nio.file.Path;
import java.util.List;
import lombok.Builder;

import static java.util.Collections.emptyList;

@Builder(toBuilder = true)
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
        return WhisperModelManagementSnapshot.builder()
                .modelRoot(modelRoot)
                .tempRoot(tempRoot)
                .catalog(WhisperModelCatalog.entries())
                .installedModels(emptyList())
                .rows(emptyList())
                .runtimeReady(true)
                .statusMessage("Download or select a Whisper.cpp model to enable transcription.")
                .operationType("")
                .operationModelId("")
                .operationModelLabel("")
                .operationStatus("")
                .build();
    }

    public boolean readyToTranscribe() {
        return runtimeReady && selectedModel != null && selectedModel.ready();
    }
}
