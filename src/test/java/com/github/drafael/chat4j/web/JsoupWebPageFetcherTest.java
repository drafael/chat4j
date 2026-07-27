package com.github.drafael.chat4j.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class JsoupWebPageFetcherTest {

    @Test
    @DisplayName("Logical cancellation closes an in-flight page fetch socket")
    void closeSocketOnCancellation_whenLogicallyCancelled_closesSocket() throws Exception {
        var closed = new CountDownLatch(1);
        Socket socket = new Socket() {
            @Override
            public synchronized void close() throws IOException {
                super.close();
                closed.countDown();
            }
        };
        var subject = new JsoupWebPageFetcher();
        Method method = JsoupWebPageFetcher.class.getDeclaredMethod(
                "closeSocketOnCancellation",
                Socket.class,
                BooleanSupplier.class
        );
        method.setAccessible(true);

        Thread watcher = (Thread) method.invoke(subject, socket, (BooleanSupplier) () -> true);
        try {
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(socket.isClosed()).isTrue();
        } finally {
            watcher.interrupt();
            socket.close();
        }
    }

    @Test
    @DisplayName("JSoup page fetcher extracts article text from fetched HTML")
    void parsePage_whenHtmlContainsPageChrome_extractsTitleBodyAndRemovesNoise() {
        JsoupWebPageFetcher subject = new JsoupWebPageFetcher();

        BrowsedPage page = subject.parsePage(
                "https://example.test/page",
                """
                        <html>
                          <head><title>Useful Page</title><script>ignored()</script></head>
                          <body>
                            <nav>navigation noise</nav>
                            <main><h1>Heading</h1><p>Useful article text.</p></main>
                            <footer>footer noise</footer>
                          </body>
                        </html>
                        """,
                200
        );

        assertThat(page.success()).isTrue();
        assertThat(page.title()).isEqualTo("Useful Page");
        assertThat(page.excerpt()).contains("Useful article text").doesNotContain("navigation noise");
    }

    @Test
    @DisplayName("JSoup page fetcher blocks non-default public ports")
    void fetch_whenUrlUsesNonDefaultPort_returnsBlockedFailure() {
        JsoupWebPageFetcher subject = new JsoupWebPageFetcher();

        BrowsedPage page = subject.fetch("https://example.com:8443/page", () -> false);

        assertThat(page.success()).isFalse();
        assertThat(page.error()).contains("URL port is not allowed");
    }

    @Test
    @DisplayName("JSoup page fetcher blocks localhost URLs before fetching")
    void fetch_whenUrlTargetsLocalhost_returnsBlockedFailure() {
        JsoupWebPageFetcher subject = new JsoupWebPageFetcher();

        BrowsedPage page = subject.fetch("http://127.0.0.1/page", () -> false);

        assertThat(page.success()).isFalse();
        assertThat(page.error()).contains("blocked network address");
    }
}
