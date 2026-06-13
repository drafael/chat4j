package com.github.drafael.chat4j.chat.conversation.webview.shared;

import org.apache.commons.lang3.StringUtils;

public record TranscriptDocumentRequest(
        boolean scrollToBottom,
        TranscriptRenderSnapshot snapshot,
        TranscriptAssetMode assetMode,
        String mermaidScriptUrl,
        String smilesDrawerScriptUrl
) {
    public TranscriptDocumentRequest {
        if (snapshot == null) {
            throw new IllegalArgumentException("Transcript render snapshot is required");
        }
        assetMode = assetMode == null ? TranscriptAssetMode.INLINE_ALL : assetMode;
        mermaidScriptUrl = StringUtils.defaultString(mermaidScriptUrl);
        smilesDrawerScriptUrl = StringUtils.defaultString(smilesDrawerScriptUrl);
    }

    @Override
    public String toString() {
        return "TranscriptDocumentRequest[scrollToBottom=%s, snapshot=%s, assetMode=%s, mermaidScriptUrl=<masked>, smilesDrawerScriptUrl=<masked>]"
                .formatted(scrollToBottom, snapshot, assetMode);
    }
}
