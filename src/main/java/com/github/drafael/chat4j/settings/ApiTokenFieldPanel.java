package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.provider.support.ApiCredentialSource;
import com.github.drafael.chat4j.provider.support.ApiCredentialStatus;
import com.github.drafael.chat4j.provider.support.CredentialMutationResult;
import com.github.drafael.chat4j.provider.support.CredentialMutationService;
import com.github.drafael.chat4j.provider.support.CredentialResolution;
import com.github.drafael.chat4j.provider.support.CredentialResolver;
import com.github.drafael.chat4j.util.Fonts;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;

import static java.util.Arrays.fill;
import static java.util.Arrays.stream;

public class ApiTokenFieldPanel extends JPanel {

    private static final int PASTE_ICON_SIZE = 16;

    private final String envVarExpression;
    private final String canonicalTokenId;
    private final ApiTokenFieldRegistry registry;
    private final SettingsCredentialChangeListener credentialChangeListener;
    private final Runnable beforeCredentialChange;
    private final Runnable afterSaveRefresh;
    private final BooleanSupplier recreateVaultConfirmation;
    private final JPasswordField tokenField = new RevealingPasswordField();
    private final JButton pasteButton = createPasteButton();
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel recreateVaultLink = new JLabel("Recreate token vault…");
    private final JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private boolean registered;
    private boolean removed;
    private boolean updating;
    private boolean dirty;
    private int pendingCompletions;
    private CompletableFuture<Boolean> saveInFlight;
    private String lastSaveError = "";

    public ApiTokenFieldPanel(
            String envVarExpression,
            ApiTokenFieldRegistry registry,
            SettingsCredentialChangeListener credentialChangeListener,
            Runnable afterSaveRefresh
    ) {
        this(envVarExpression, registry, credentialChangeListener, null, afterSaveRefresh, null);
    }

    public ApiTokenFieldPanel(
            String envVarExpression,
            ApiTokenFieldRegistry registry,
            SettingsCredentialChangeListener credentialChangeListener,
            Runnable beforeCredentialChange,
            Runnable afterSaveRefresh
    ) {
        this(envVarExpression, registry, credentialChangeListener, beforeCredentialChange, afterSaveRefresh, null);
    }

    ApiTokenFieldPanel(
            String envVarExpression,
            ApiTokenFieldRegistry registry,
            SettingsCredentialChangeListener credentialChangeListener,
            Runnable afterSaveRefresh,
            BooleanSupplier recreateVaultConfirmation
    ) {
        this(envVarExpression, registry, credentialChangeListener, null, afterSaveRefresh, recreateVaultConfirmation);
    }

    private ApiTokenFieldPanel(
            String envVarExpression,
            ApiTokenFieldRegistry registry,
            SettingsCredentialChangeListener credentialChangeListener,
            Runnable beforeCredentialChange,
            Runnable afterSaveRefresh,
            BooleanSupplier recreateVaultConfirmation
    ) {
        this.envVarExpression = envVarExpression;
        this.canonicalTokenId = CredentialResolver.canonicalTokenId(envVarExpression);
        this.registry = registry;
        this.credentialChangeListener = credentialChangeListener == null
                ? SettingsCredentialChangeListener.NO_OP
                : credentialChangeListener;
        this.beforeCredentialChange = beforeCredentialChange == null ? () -> { } : beforeCredentialChange;
        this.afterSaveRefresh = afterSaveRefresh == null ? () -> { } : afterSaveRefresh;
        this.recreateVaultConfirmation = recreateVaultConfirmation == null ? this::confirmRecreateVault : recreateVaultConfirmation;
        buildUi();
        registerField();
        reloadFromEffectiveCredential();
    }

    @Override
    public void addNotify() {
        boolean readded = removed;
        removed = false;
        registerField();
        super.addNotify();
        if (readded && pendingCompletions == 0) {
            reloadFromEffectiveCredential();
        }
    }

    @Override
    public void removeNotify() {
        removed = true;
        updating = true;
        try {
            tokenField.setText("");
            dirty = false;
        } finally {
            updating = false;
            unregisterFromRegistry();
            super.removeNotify();
        }
    }

    public String canonicalTokenId() {
        return canonicalTokenId;
    }

    public boolean dirty() {
        return dirty;
    }

    public String lastSaveError() {
        return lastSaveError;
    }

    boolean hasDifferentPendingValue(ApiTokenFieldPanel other) {
        if (other == null) {
            return true;
        }
        char[] currentPassword = tokenField.getPassword();
        char[] otherPassword = other.tokenField.getPassword();
        try {
            return !Arrays.equals(currentPassword, otherPassword);
        } finally {
            fill(currentPassword, '\0');
            fill(otherPassword, '\0');
        }
    }

    public CompletableFuture<Boolean> savePendingChangesAsync() {
        if (saveInFlight != null) {
            return saveInFlight;
        }
        return dirty ? saveTokenAsync() : CompletableFuture.completedFuture(true);
    }

    void prepareForCredentialChange() {
        beforeCredentialChange.run();
    }

    void reloadAfterPeerCredentialChanged() {
        reloadFromEffectiveCredential();
        afterSaveRefresh.run();
    }

    public void reloadFromEffectiveCredential() {
        setControlsEnabled(false);
        applyEffectiveCredential(new EffectiveCredential(
                CredentialResolver.resolveCredential(envVarExpression, null),
                CredentialResolver.resolveCredentialStatus(envVarExpression, null)
        ));
    }

    private void applyEffectiveCredential(EffectiveCredential effectiveCredential) {
        updating = true;
        try {
            tokenField.setText(effectiveCredential.resolution().hasValue() ? effectiveCredential.resolution().value() : "");
            dirty = false;
            applyStatus(effectiveCredential.status());
        } finally {
            updating = false;
            setControlsEnabled(true);
        }
    }

    private void buildUi() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);

        tokenField.setColumns(34);
        tokenField.setMaximumSize(new Dimension(Integer.MAX_VALUE, tokenField.getPreferredSize().height));
        tokenField.putClientProperty("JTextField.placeholderText", "Paste API token");
        tokenField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        tokenField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_COMPONENT, pasteButton);
        tokenField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                markDirty();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                markDirty();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                markDirty();
            }
        });
        tokenField.addActionListener(e -> saveIfDirty());
        tokenField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (!e.isTemporary() && !focusMovedInsideTokenField(e)) {
                    saveIfDirty();
                }
            }
        });
        tokenField.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(tokenField);

        statusRow.setOpaque(false);
        statusRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        statusRow.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

        Fonts.apply(statusLabel, Font.PLAIN, Fonts.SIZE_SMALL);
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        statusRow.add(statusLabel);

        Fonts.apply(recreateVaultLink, Font.PLAIN, Fonts.SIZE_SMALL);
        recreateVaultLink.setForeground(uiColor("Component.linkColor", "Label.foreground"));
        recreateVaultLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        recreateVaultLink.setVisible(false);
        recreateVaultLink.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                requestVaultRecreation();
            }
        });
        statusRow.add(recreateVaultLink);
        statusRow.setVisible(false);
        add(statusRow);
    }

    private void registerField() {
        if (!registered) {
            registry.register(this);
            registered = true;
        }
    }

    void unregisterFromRegistry() {
        if (registered) {
            registry.unregister(this);
            registered = false;
        }
    }

    private boolean focusMovedInsideTokenField(FocusEvent e) {
        Component opposite = e.getOppositeComponent();
        return opposite != null && SwingUtilities.isDescendingFrom(opposite, tokenField);
    }

    private void saveIfDirty() {
        if (dirty && tokenField.isEnabled()) {
            saveTokenAsync();
        }
    }

    private void markDirty() {
        if (updating) {
            return;
        }
        dirty = true;
        showStatus("Token changed; saving when field loses focus", uiColor("Component.warning.foreground", "Component.warning.focusedBorderColor", "Actions.Yellow", "Label.foreground"));
    }

    private CompletableFuture<Boolean> saveTokenAsync() {
        if (saveInFlight != null) {
            return saveInFlight;
        }
        String conflict = registry.conflictMessage(this);
        if (StringUtils.isNotBlank(conflict)) {
            lastSaveError = conflict;
            setError(conflict);
            return CompletableFuture.completedFuture(false);
        }
        try {
            registry.broadcastCredentialChanging(canonicalTokenId);
        } catch (Exception e) {
            return failedCompletion(e);
        }
        char[] password = tokenField.getPassword();
        pendingCompletions++;
        setControlsEnabled(false);
        setInfo("Saving token...");
        CompletableFuture<Boolean> operation = CompletableFuture.supplyAsync(() -> {
            try {
                CredentialMutationResult result = CredentialMutationService.shared().saveTokenOverride(
                        envVarExpression,
                        password,
                        this::notifyCredentialMutation
                );
                return new SaveOutcome(result.successful(), result, result.message());
            } catch (Exception e) {
                return new SaveOutcome(false, null, StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
            } finally {
                fill(password, '\0');
            }
        }).thenCompose(this::completeSaveOutcomeAsync);
        saveInFlight = operation;
        operation.whenComplete((ignored, error) -> clearSaveInFlight(operation));
        return operation;
    }

    CompletableFuture<Boolean> requestVaultRecreation() {
        if (!recreateVaultLink.isVisible() || !recreateVaultLink.isEnabled() || !recreateVaultConfirmation.getAsBoolean()) {
            return CompletableFuture.completedFuture(false);
        }
        return recreateVaultAsync();
    }

    private boolean confirmRecreateVault() {
        int result = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(this),
                "%s %s %s%s".formatted(
                        "Repairing/recreating the token vault will make all saved API tokens unavailable",
                        "until they are re-entered.",
                        "Bounded backups of existing regular vault and key files will be retained.",
                        "\n\nRepair/recreate token vault?"
                ),
                "Repair/recreate token vault?",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        return result == JOptionPane.OK_OPTION;
    }

    private CompletableFuture<Boolean> recreateVaultAsync() {
        try {
            registry.broadcastAllCredentialsChanging();
        } catch (Exception e) {
            return failedCompletion(e);
        }
        pendingCompletions++;
        setControlsEnabled(false);
        setInfo("Recreating token vault...");
        return CompletableFuture.supplyAsync(() -> CredentialMutationService.shared().recreateVault(
                this::notifyCredentialMutation
        )).thenCompose(this::completeVaultRecreationAsync);
    }

    private CompletableFuture<Boolean> failedCompletion(Throwable error) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        failCompletion(error, result);
        return result;
    }

    private void notifyCredentialMutation(CredentialMutationResult mutation) {
        if (!mutation.requiresDependentRefresh()) {
            return;
        }
        var failures = new ArrayList<RuntimeException>();
        mutation.affectedTokenIds().forEach(tokenId -> {
            try {
                credentialChangeListener.credentialChanged(tokenId);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        });
        if (!failures.isEmpty()) {
            var failure = new IllegalStateException("Credential change dependent refresh failed.");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
    }

    private CompletableFuture<Boolean> completeSaveOutcomeAsync(SaveOutcome outcome) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            if (removed) {
                boolean successful = completeRemovedSaveOutcome(outcome);
                pendingCompletions--;
                result.complete(successful);
                return;
            }
            try {
                finishSaveOutcome(outcome, result);
            } finally {
                pendingCompletions--;
            }
        });
        return result;
    }

    private boolean completeRemovedSaveOutcome(SaveOutcome outcome) {
        if (outcome.result() == null || !outcome.result().requiresDependentRefresh()) {
            return outcome.success();
        }
        try {
            registry.broadcastSaved(this, canonicalTokenId);
            return outcome.success();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void finishSaveOutcome(SaveOutcome outcome, CompletableFuture<Boolean> result) {
        if (!outcome.success()) {
            if (outcome.result() != null) {
                reloadAfterFailedMutation(outcome.result(), false);
            }
            failCompletion(outcome.errorMessage(), result);
            return;
        }
        try {
            reloadFromEffectiveCredential();
            if (outcome.result().applied()) {
                registry.broadcastSaved(this, canonicalTokenId);
            }
            afterSaveRefresh.run();
            setControlsEnabled(true);
            dirty = false;
            lastSaveError = outcome.errorMessage();
            if (StringUtils.isNotBlank(outcome.errorMessage())) {
                setError(outcome.errorMessage());
            }
            result.complete(true);
        } catch (Exception e) {
            dirty = false;
            failCompletion(e, result);
        }
    }

    private void clearSaveInFlight(CompletableFuture<Boolean> operation) {
        Runnable clear = () -> {
            if (saveInFlight == operation) {
                saveInFlight = null;
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            clear.run();
        } else {
            SwingUtilities.invokeLater(clear);
        }
    }

    private CompletableFuture<Boolean> completeVaultRecreationAsync(CredentialMutationResult mutation) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            if (removed) {
                boolean successful = completeRemovedVaultRecreation(mutation);
                pendingCompletions--;
                result.complete(successful);
                return;
            }
            try {
                finishVaultRecreation(mutation, result);
            } finally {
                pendingCompletions--;
            }
        });
        return result;
    }

    private boolean completeRemovedVaultRecreation(CredentialMutationResult mutation) {
        if (!mutation.requiresDependentRefresh()) {
            return mutation.successful();
        }
        try {
            registry.broadcastVaultRecreated(this);
            return mutation.successful();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void finishVaultRecreation(CredentialMutationResult mutation, CompletableFuture<Boolean> result) {
        if (!mutation.successful()) {
            reloadAfterFailedMutation(mutation, true);
            failCompletion(mutation.message(), result);
            return;
        }
        try {
            recreateVaultLink.setVisible(false);
            reloadFromEffectiveCredential();
            if (mutation.applied()) {
                registry.broadcastVaultRecreated(this);
            }
            afterSaveRefresh.run();
            setControlsEnabled(true);
            lastSaveError = mutation.message();
            if (StringUtils.isNotBlank(mutation.message())) {
                setError(mutation.message());
            }
            result.complete(true);
        } catch (Exception e) {
            failCompletion(e, result);
        }
    }

    private void reloadAfterFailedMutation(CredentialMutationResult mutation, boolean recreation) {
        if (!mutation.requiresDependentRefresh()) {
            return;
        }
        try {
            reloadFromEffectiveCredential();
            if (recreation) {
                registry.broadcastVaultRecreated(this);
            } else {
                registry.broadcastSaved(this, canonicalTokenId);
            }
            afterSaveRefresh.run();
        } catch (RuntimeException ignored) {
            // The sanitized mutation failure remains the primary user-facing error.
        }
    }

    private void failCompletion(Throwable error, CompletableFuture<Boolean> result) {
        failCompletion(errorMessage(error), result);
    }

    private void failCompletion(String message, CompletableFuture<Boolean> result) {
        lastSaveError = message;
        setError(message);
        setControlsEnabled(true);
        result.complete(false);
    }

    private String errorMessage(Throwable error) {
        Throwable cause = error instanceof CompletionException completionException && completionException.getCause() != null
                ? completionException.getCause()
                : error;
        return StringUtils.defaultIfBlank(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private void setControlsEnabled(boolean enabled) {
        tokenField.setEnabled(enabled);
        pasteButton.setEnabled(enabled);
        recreateVaultLink.setEnabled(enabled);
    }

    private void applyStatus(ApiCredentialStatus status) {
        recreateVaultLink.setVisible(false);
        if (status.source() == ApiCredentialSource.ERROR) {
            recreateVaultLink.setVisible(true);
            setError("Could not read saved token");
            return;
        }
        hideStatus();
    }

    private void setInfo(String message) {
        showStatus(message, UIManager.getColor("Label.disabledForeground"));
    }

    private void setError(String message) {
        showStatus(message, uiColor("Component.error.foreground", "Component.error.focusedBorderColor", "Actions.Red", "Label.foreground"));
    }

    private void showStatus(String message, Color color) {
        statusLabel.setText(message);
        statusLabel.setForeground(color == null ? UIManager.getColor("Label.disabledForeground") : color);
        statusRow.setVisible(true);
        revalidate();
        repaint();
    }

    private void hideStatus() {
        statusLabel.setText(" ");
        statusRow.setVisible(false);
        revalidate();
        repaint();
    }

    private JButton createPasteButton() {
        JButton button = new JButton(loadPasteIcon());
        if (button.getIcon() == null) {
            button.setText("Paste");
        }
        button.setToolTipText("Paste API token");
        button.getAccessibleContext().setAccessibleName("Paste API token");
        button.setFocusable(false);
        button.addActionListener(e -> {
            if (!button.isEnabled() || !tokenField.isEnabled()) {
                return;
            }
            tokenField.requestFocusInWindow();
            tokenField.paste();
        });
        return button;
    }

    private Icon loadPasteIcon() {
        Icon flatLafIcon = flatLafPasteIcon();
        if (flatLafIcon != null) {
            return flatLafIcon;
        }
        URL url = ApiTokenFieldPanel.class.getResource("/icons/input/clipboard-paste.svg");
        if (url == null) {
            return null;
        }
        FlatSVGIcon icon = new FlatSVGIcon(url).derive(PASTE_ICON_SIZE, PASTE_ICON_SIZE);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter((component, color) -> pasteIconColor()));
        return icon;
    }

    private Color pasteIconColor() {
        Color revealIconColor = UIManager.getColor("PasswordField.revealIconColor");
        if (revealIconColor != null) {
            return revealIconColor;
        }
        Color clearIconColor = UIManager.getColor("SearchField.clearIconColor");
        return clearIconColor == null ? UIManager.getColor("Label.disabledForeground") : clearIconColor;
    }

    private Icon flatLafPasteIcon() {
        return stream(new String[] {
                        "Actions.Paste",
                        "Actions.PasteIcon",
                        "Actions.MenuPaste",
                        "Actions.MenuPasteIcon",
                        "MenuItem.pasteIcon"
                })
                .map(UIManager::getIcon)
                .filter(icon -> icon != null)
                .findFirst()
                .orElse(null);
    }

    private Color uiColor(String... keys) {
        for (String key : keys) {
            Color color = UIManager.getColor(key);
            if (color != null) {
                return color;
            }
        }
        return getForeground();
    }

    private record EffectiveCredential(CredentialResolution resolution, ApiCredentialStatus status) {
    }

    private record SaveOutcome(boolean success, CredentialMutationResult result, String errorMessage) {
    }

    private static class RevealingPasswordField extends JPasswordField {
        @Override
        public void updateUI() {
            Object original = UIManager.get("PasswordField.showRevealButton");
            UIManager.put("PasswordField.showRevealButton", Boolean.TRUE);
            try {
                super.updateUI();
            } finally {
                UIManager.put("PasswordField.showRevealButton", original);
            }
        }
    }
}
