package com.github.drafael.chat4j.chat.agent;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class McpSwingApprovalHandler implements McpApprovalHandler {

    @NonNull
    private final Supplier<Window> ownerSupplier;

    @Override
    public McpApprovalDecision requestApproval(
            @NonNull McpApprovalRequest request,
            @NonNull BooleanSupplier cancelled
    ) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("MCP approval must not block the EDT.");
        }
        if (cancelled.getAsBoolean()) {
            return McpApprovalDecision.DENY;
        }
        CompletableFuture<McpApprovalDecision> decision = new CompletableFuture<>();
        AtomicReference<JDialog> dialogReference = new AtomicReference<>();
        SwingUtilities.invokeLater(() -> showDialog(request, decision, dialogReference));
        try {
            while (true) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                    denyAndDispose(decision, dialogReference);
                    return McpApprovalDecision.DENY;
                }
                try {
                    return decision.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            denyAndDispose(decision, dialogReference);
            return McpApprovalDecision.DENY;
        } catch (Exception e) {
            denyAndDispose(decision, dialogReference);
            return McpApprovalDecision.DENY;
        }
    }

    private void showDialog(
            McpApprovalRequest request,
            CompletableFuture<McpApprovalDecision> decision,
            AtomicReference<JDialog> dialogReference
    ) {
        if (decision.isDone()) {
            return;
        }
        Window owner = ownerSupplier.get();
        if (decision.isDone()) {
            return;
        }
        if (owner != null && !owner.isDisplayable()) {
            decision.complete(McpApprovalDecision.DENY);
            return;
        }
        JDialog dialog = new JDialog(owner, "Allow MCP Tool?", Dialog.ModalityType.APPLICATION_MODAL);
        dialogReference.set(dialog);
        if (decision.isDone()) {
            dialog.dispose();
            return;
        }
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setLayout(new BorderLayout(10, 10));
        dialog.add(new JLabel("%s wants to run %s".formatted(request.serverName(), request.toolName())), BorderLayout.NORTH);

        JTextArea arguments = new JTextArea(request.arguments(), 14, 60);
        arguments.setEditable(false);
        arguments.setLineWrap(false);
        dialog.add(new JScrollPane(arguments), BorderLayout.CENTER);

        JButton deny = new JButton("Deny");
        JButton allow = new JButton("Allow once");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(deny);
        actions.add(allow);
        dialog.add(actions, BorderLayout.SOUTH);

        deny.addActionListener(event -> resolve(dialog, decision, McpApprovalDecision.DENY));
        allow.addActionListener(event -> resolve(dialog, decision, McpApprovalDecision.ALLOW_ONCE));
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                decision.complete(McpApprovalDecision.DENY);
            }
        });
        dialog.getRootPane().registerKeyboardAction(
                event -> resolve(dialog, decision, McpApprovalDecision.DENY),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        if (decision.isDone() || owner != null && !owner.isDisplayable()) {
            decision.complete(McpApprovalDecision.DENY);
            dialog.dispose();
            return;
        }
        dialog.setVisible(true);
    }

    private void resolve(
            JDialog dialog,
            CompletableFuture<McpApprovalDecision> decision,
            McpApprovalDecision value
    ) {
        if (decision.complete(value)) {
            dialog.dispose();
        }
    }

    private void denyAndDispose(
            CompletableFuture<McpApprovalDecision> decision,
            AtomicReference<JDialog> dialogReference
    ) {
        decision.complete(McpApprovalDecision.DENY);
        SwingUtilities.invokeLater(() -> dispose(dialogReference.get()));
    }

    private void dispose(JDialog dialog) {
        if (dialog != null && dialog.isDisplayable()) {
            dialog.dispose();
        }
    }
}
