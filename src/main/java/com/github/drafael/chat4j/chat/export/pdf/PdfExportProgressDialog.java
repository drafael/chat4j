package com.github.drafael.chat4j.chat.export.pdf;

import com.github.drafael.chat4j.util.Fonts;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import lombok.NonNull;

import static com.github.drafael.chat4j.util.ModalDialogSupport.configureTitlelessDialog;

public final class PdfExportProgressDialog {

    private final JDialog dialog;
    private final JLabel stageLabel = new JLabel("Preparing conversation");
    private final JButton cancelButton = new JButton("Cancel");
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private BooleanSupplier cancelAction = () -> true;

    public PdfExportProgressDialog(@NonNull Window owner) {
        dialog = new JDialog(owner, Dialog.ModalityType.MODELESS);
        configureTitlelessDialog(dialog);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cancel();
            }
        });

        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        Fonts.apply(stageLabel, java.awt.Font.PLAIN, Fonts.SIZE_BODY);
        content.add(stageLabel, BorderLayout.NORTH);

        JProgressBar progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setPreferredSize(new Dimension(360, 8));
        content.add(progressBar, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        cancelButton.addActionListener(e -> cancel());
        actions.add(cancelButton);
        content.add(actions, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
    }

    public void show() {
        requireEdt();
        dialog.setVisible(true);
    }

    public void setStage(@NonNull ConversationPdfExportService.ExportStage stage) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> setStage(stage));
            return;
        }
        if (dialog.isDisplayable()) {
            stageLabel.setText(stage.displayName());
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void setCancelAction(@NonNull BooleanSupplier cancelAction) {
        requireEdt();
        this.cancelAction = cancelAction;
    }

    public void close() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::close);
            return;
        }
        dialog.dispose();
    }

    private void cancel() {
        cancelButton.setEnabled(false);
        if (cancelAction.getAsBoolean()) {
            cancelled.set(true);
            stageLabel.setText("Cancelling…");
        } else {
            stageLabel.setText("Finalizing");
        }
    }

    private void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("PDF export progress dialog must be shown on the EDT");
        }
    }
}
