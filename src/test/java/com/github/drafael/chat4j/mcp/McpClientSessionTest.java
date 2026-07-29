package com.github.drafael.chat4j.mcp;

import com.github.drafael.chat4j.chat.agent.McpInvocationPermit;
import java.net.URI;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpClientSessionTest {

    @Test
    @DisplayName("Cancellation that wins before subscription admits no transport send")
    void awaitUntil_whenAlreadyCancelled_doesNotSubscribeToOperation() throws Exception {
        var subscriptions = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        var admissionCheckEntered = new CountDownLatch(1);
        var releaseAdmissionCheck = new CountDownLatch(1);
        Mono<String> operation = Mono.defer(() -> {
            subscriptions.incrementAndGet();
            return Mono.just("sent");
        });
        CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
            try {
                return McpClientSession.awaitUntil(
                        operation,
                        Long.MAX_VALUE,
                        () -> {
                            admissionCheckEntered.countDown();
                            try {
                                releaseAdmissionCheck.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return cancelled.get();
                        },
                        System::nanoTime
                );
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        try {
            assertThat(admissionCheckEntered.await(5, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
        } finally {
            cancelled.set(true);
            releaseAdmissionCheck.countDown();
            result.handle((ignored, error) -> null).join();
        }

        assertThatThrownBy(result::join).hasRootCauseInstanceOf(CancellationException.class);
        assertThat(subscriptions).hasValue(0);
    }

    @Test
    @DisplayName("Cancellation winning one-shot send admission prevents SDK call creation and subscription")
    void admittedCall_whenCancellationWinsPermitRace_doesNotCreateSdkOperation() throws Exception {
        var permit = McpInvocationPermit.pendingApproval();
        assertThat(permit.allowOnce()).isTrue();
        var cancelled = new AtomicBoolean();
        var admissionCheckEntered = new CountDownLatch(1);
        var releaseAdmissionCheck = new CountDownLatch(1);
        var sdkCalls = new AtomicInteger();
        CompletableFuture<CompletableFuture<String>> result = CompletableFuture.supplyAsync(() ->
                McpClientSession.admittedCall(
                        permit,
                        () -> {
                            admissionCheckEntered.countDown();
                            try {
                                releaseAdmissionCheck.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return cancelled.get();
                        },
                        () -> {
                            sdkCalls.incrementAndGet();
                            return Mono.just("sent");
                        }
                ));
        try {
            assertThat(admissionCheckEntered.await(5, TimeUnit.SECONDS)).isTrue();
            cancelled.set(true);
        } finally {
            cancelled.set(true);
            releaseAdmissionCheck.countDown();
            result.handle((ignored, error) -> null).join();
        }

        assertThatThrownBy(result::join).hasRootCauseInstanceOf(CancellationException.class);
        assertThat(sdkCalls).hasValue(0);
    }

    @Test
    @DisplayName("A consumed invocation permit admits exactly one SDK call")
    void admittedCall_whenPermitWasConsumed_rejectsSecondSdkOperation() {
        var permit = McpInvocationPermit.automaticallyAllowed();
        var sdkCalls = new AtomicInteger();

        assertThat(McpClientSession.admittedCall(
                permit,
                () -> false,
                () -> {
                    sdkCalls.incrementAndGet();
                    return Mono.just("sent");
                }
        ).join()).isEqualTo("sent");
        assertThatThrownBy(() -> McpClientSession.admittedCall(
                permit,
                () -> false,
                () -> {
                    sdkCalls.incrementAndGet();
                    return Mono.just("replayed");
                }
        )).isInstanceOf(CancellationException.class);

        assertThat(sdkCalls).hasValue(1);
    }

    @Test
    @DisplayName("Origin base preserves explicit scheme default ports for SDK authority matching")
    void originBase_whenEndpointUsesExplicitDefaultPort_preservesRawAuthority() {
        assertThat(McpClientSession.originBase(URI.create("http://example.test:80/mcp")))
                .isEqualTo("http://example.test:80");
        assertThat(McpClientSession.originBase(URI.create("https://example.test:443/mcp")))
                .isEqualTo("https://example.test:443");
        assertThat(McpClientSession.originBase(URI.create("http://example.test/mcp")))
                .isEqualTo("http://example.test");
    }
}
