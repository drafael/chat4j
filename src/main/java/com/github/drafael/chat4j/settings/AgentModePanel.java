package com.github.drafael.chat4j.settings;

import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.concurrent.CompletableFuture;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

public class AgentModePanel extends AbstractSettingsPanel implements AsyncPendingSettingsSaveParticipant {

    private static final int PROMPT_SAVE_DEBOUNCE_MILLIS = 300;

    private final AgentModeSettings agentModeSettings;
    private final SettingsWriteQueue writeQueue = new SettingsWriteQueue("agent-mode-settings-save-");
    private final JTextArea promptAppendArea;
    private final Timer promptSaveTimer;
    private SaveRequest latestRequest;
    private SaveRequest failedRequest;
    private String lastEnqueuedPrompt;
    private String lastSaveError = "";
    private boolean disposed;

    public AgentModePanel(SettingsRepository settingsRepo) {
        super(settingsRepo);
        agentModeSettings = new AgentModeSettings(settingsRepo);

        JPanel form = createFormPanel("Agent Mode");
        GridBagConstraints gbc = createFormConstraints();
        int row = 0;

        promptAppendArea = new JTextArea(6, 40);
        promptAppendArea.setName("agentSystemPromptAppendArea");
        promptAppendArea.setLineWrap(true);
        promptAppendArea.setWrapStyleWord(true);
        promptAppendArea.setText(agentModeSettings.resolveSystemPromptAppend());
        lastEnqueuedPrompt = promptAppendArea.getText();

        JScrollPane promptScrollPane = new JScrollPane(promptAppendArea);
        promptScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        promptScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        promptScrollPane.setPreferredSize(new Dimension(420, 130));
        addRow(form, gbc, row++, "Prompt addendum", promptScrollPane);
        row = addSectionHint(form, gbc, row, "Prompt addendum is appended to the default Agent Mode system prompt.");
        addVerticalSpacer(form, gbc, row);

        promptSaveTimer = new Timer(PROMPT_SAVE_DEBOUNCE_MILLIS, e -> enqueuePromptSave());
        promptSaveTimer.setRepeats(false);
        installPromptPersistence();
    }

    private void installPromptPersistence() {
        promptAppendArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                enqueuePromptSave();
            }
        });
        promptAppendArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                promptSaveTimer.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                promptSaveTimer.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                promptSaveTimer.restart();
            }
        });
    }

    private void enqueuePromptSave() {
        promptSaveTimer.stop();
        String prompt = promptAppendArea.getText();
        if (prompt.equals(lastEnqueuedPrompt)) {
            return;
        }
        lastEnqueuedPrompt = prompt;
        enqueueSave(() -> agentModeSettings.persistSystemPromptAppend(prompt), false);
    }

    private void enqueueSave(Runnable mutation, boolean retry) {
        if (disposed) {
            return;
        }
        var completion = new CompletableFuture<Void>();
        var request = new SaveRequest(mutation, completion);
        latestRequest = request;
        if (!retry) {
            failedRequest = null;
            lastSaveError = "";
        }
        writeQueue.submit(mutation).whenComplete((ignored, error) ->
                SwingUtilities.invokeLater(() -> finishSave(request, error)));
    }

    private void finishSave(SaveRequest request, Throwable writeError) {
        Error fatalError = SettingsWriteQueue.fatalError(writeError);
        try {
            if (latestRequest == request) {
                if (writeError == null) {
                    failedRequest = null;
                    lastSaveError = "";
                    if (!disposed) {
                        setStatusInfo(STATUS_SAVED);
                    }
                } else {
                    failedRequest = request;
                    lastSaveError = "Failed to save prompt addendum setting";
                    if (!disposed) {
                        setStatusError(lastSaveError);
                    }
                }
            }
        } finally {
            request.completion().complete(null);
        }
        if (fatalError != null) {
            throw fatalError;
        }
    }

    @Override
    public CompletableFuture<Boolean> savePendingChangesAsync() {
        enqueuePromptSave();
        if (failedRequest != null) {
            enqueueSave(failedRequest.mutation(), true);
        }
        var result = new CompletableFuture<Boolean>();
        awaitStableSave(result);
        return result;
    }

    private void awaitStableSave(CompletableFuture<Boolean> result) {
        SaveRequest observed = latestRequest;
        CompletableFuture<Void> completion = observed == null
                ? CompletableFuture.completedFuture(null)
                : observed.completion();
        completion.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (latestRequest != observed) {
                awaitStableSave(result);
            } else {
                result.complete(failedRequest == null);
            }
        }));
    }

    @Override
    public String lastSaveError() {
        return lastSaveError;
    }

    @Override
    public String settingsSectionName() {
        return "Agent Mode settings";
    }

    void disposePanel() {
        if (disposed) {
            return;
        }
        disposed = true;
        promptSaveTimer.stop();
        writeQueue.close();
        disposeSettingsPanel();
    }

    private record SaveRequest(Runnable mutation, CompletableFuture<Void> completion) {
    }
}
