package com.github.drafael.chat4j.chat.agent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpSwingApprovalHandlerTest {

    @Test
    @DisplayName("Cancellation before the EDT shows approval prevents dialog creation")
    void requestApproval_whenCancelledBeforeShow_completesDenyBeforeQueuedCallback() throws Exception {
        var edtBlocked = new CountDownLatch(1);
        var releaseEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                releaseEdt.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        CompletableFuture<McpApprovalDecision> approval = null;
        var cancelled = new AtomicBoolean();
        var ownerLookups = new AtomicInteger();
        try {
            assertThat(edtBlocked.await(5, TimeUnit.SECONDS)).isTrue();
            var cancellationPolled = new CountDownLatch(1);
            var cancellationChecks = new AtomicInteger();
            var subject = new McpSwingApprovalHandler(() -> {
                ownerLookups.incrementAndGet();
                return null;
            });
            approval = CompletableFuture.supplyAsync(() ->
                    subject.requestApproval(
                            new McpApprovalRequest("Server", "tool", "{\"value\":\"secret\"}"),
                            () -> {
                                if (cancellationChecks.incrementAndGet() > 1) {
                                    cancellationPolled.countDown();
                                }
                                return cancelled.get();
                            }
                    ));
            assertThat(cancellationPolled.await(5, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);

            assertThat(approval.get(5, TimeUnit.SECONDS)).isEqualTo(McpApprovalDecision.DENY);
            assertThat(ownerLookups).hasValue(0);
        } finally {
            cancelled.set(true);
            releaseEdt.countDown();
            if (approval != null) {
                approval.get(5, TimeUnit.SECONDS);
            }
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    @Test
    @DisplayName("Cancellation while owner lookup is in progress prevents modal construction")
    void requestApproval_whenCancelledDuringConstruction_doesNotShowDialog() throws Exception {
        var ownerLookupEntered = new CountDownLatch(1);
        var releaseOwnerLookup = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var subject = new McpSwingApprovalHandler(() -> {
            ownerLookupEntered.countDown();
            try {
                releaseOwnerLookup.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        CompletableFuture<McpApprovalDecision> approval = CompletableFuture.supplyAsync(() ->
                subject.requestApproval(
                        new McpApprovalRequest("Server", "tool", "{}"),
                        cancelled::get
                ));
        try {
            assertThat(ownerLookupEntered.await(5, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
            assertThat(approval.get(5, TimeUnit.SECONDS)).isEqualTo(McpApprovalDecision.DENY);
        } finally {
            cancelled.set(true);
            releaseOwnerLookup.countDown();
            approval.get(5, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> { });
        }
    }
}
