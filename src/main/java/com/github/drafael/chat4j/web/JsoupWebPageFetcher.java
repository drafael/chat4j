package com.github.drafael.chat4j.web;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static java.util.stream.Collectors.toMap;

public class JsoupWebPageFetcher implements WebPageFetcher {

    private static final int MAX_BODY_SIZE_BYTES = 1_500_000;
    private static final int MAX_HEADER_SIZE_BYTES = 64 * 1024;
    private static final int MAX_ASCII_LINE_SIZE_BYTES = 8 * 1024;
    private static final int EXCERPT_LIMIT = 2_400;
    private static final int MAX_REDIRECTS = 5;
    private static final int HTTP_PORT = 80;
    private static final int HTTPS_PORT = 443;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chat4J/1.0 Safari/537.36";

    @Override
    public BrowsedPage fetch(String url, BooleanSupplier isCancelled) {
        if (StringUtils.isBlank(url)) {
            return failed(url, "Blank URL");
        }
        if (shouldStop(isCancelled)) {
            return failed(url, "Cancelled");
        }

        try {
            String currentUrl = url;
            for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
                if (shouldStop(isCancelled)) {
                    return failed(currentUrl, "Cancelled");
                }

                FetchTarget target = validateFetchableUrl(currentUrl);
                FetchResponse response = fetch(target, isCancelled);
                if (response.isRedirect()) {
                    currentUrl = resolveRedirect(target.uri(), response.location());
                    continue;
                }

                return parsePage(target.uri().toString(), response.body(), response.statusCode());
            }

            return failed(url, "Too many redirects");
        } catch (Exception e) {
            return failed(url, StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    BrowsedPage parsePage(String url, String html, int statusCode) {
        Document document = Jsoup.parse(StringUtils.defaultString(html), url);
        clean(document);
        String title = StringUtils.defaultIfBlank(document.title(), domain(url));
        String text = normalizeText(document.body() == null ? document.text() : document.body().text());
        return new BrowsedPage(title, url, domain(url), cap(text), statusCode < 400, statusCode >= 400 ? "HTTP %d".formatted(statusCode) : "");
    }

    private FetchTarget validateFetchableUrl(String url) throws Exception {
        URI uri = URI.create(StringUtils.trimToEmpty(url)).normalize();
        String scheme = StringUtils.lowerCase(uri.getScheme());
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new IllegalArgumentException("URL scheme is not allowed");
        }
        if (StringUtils.isBlank(uri.getHost()) || StringUtils.isNotBlank(uri.getUserInfo())) {
            throw new IllegalArgumentException("URL host is not allowed");
        }
        if (!isAllowedPort(uri)) {
            throw new IllegalArgumentException("URL port is not allowed");
        }

        InetAddress address = Arrays.stream(InetAddress.getAllByName(uri.getHost()))
                .filter(candidate -> !isBlockedAddress(candidate))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("URL host resolves to a blocked network address"));
        return new FetchTarget(uri, address, uri.getHost(), resolvePort(uri), StringUtils.equals(scheme, "https"));
    }

    private FetchResponse fetch(FetchTarget target, BooleanSupplier isCancelled) throws Exception {
        try (Socket socket = openSocket(target)) {
            socket.setSoTimeout((int) Duration.ofSeconds(12).toMillis());
            writeRequest(socket.getOutputStream(), target);

            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            String headerText = readHeaders(input);
            int statusCode = statusCode(headerText);
            Map<String, String> headers = headers(headerText);
            String body = readBody(input, headers);
            if (shouldStop(isCancelled)) {
                Thread.currentThread().interrupt();
                throw new InterruptedException("Web page fetch cancelled");
            }
            return new FetchResponse(statusCode, headers.getOrDefault("location", ""), body);
        }
    }

    private Socket openSocket(FetchTarget target) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(target.address(), target.port()), (int) Duration.ofSeconds(12).toMillis());
        if (!target.secure()) {
            return socket;
        }

        SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket sslSocket = (SSLSocket) sslSocketFactory.createSocket(socket, target.host(), target.port(), true);
        var sslParameters = sslSocket.getSSLParameters();
        sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!isIpLiteral(target.host())) {
            sslParameters.setServerNames(List.of(new SNIHostName(target.host())));
        }
        sslSocket.setSSLParameters(sslParameters);
        sslSocket.startHandshake();
        return sslSocket;
    }

    private void writeRequest(OutputStream output, FetchTarget target) throws IOException {
        output.write("GET %s HTTP/1.1\r\n".formatted(requestTarget(target.uri())).getBytes(StandardCharsets.ISO_8859_1));
        output.write("Host: %s\r\n".formatted(hostHeader(target)).getBytes(StandardCharsets.ISO_8859_1));
        output.write("User-Agent: %s\r\n".formatted(USER_AGENT).getBytes(StandardCharsets.ISO_8859_1));
        output.write("Accept: text/html,application/xhtml+xml,text/plain;q=0.8,*/*;q=0.1\r\n".getBytes(StandardCharsets.ISO_8859_1));
        output.write("Accept-Encoding: identity\r\n".getBytes(StandardCharsets.ISO_8859_1));
        output.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private String readHeaders(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int previous3 = -1;
        int previous2 = -1;
        int previous1 = -1;
        int current;
        while ((current = input.read()) != -1) {
            buffer.write(current);
            if (buffer.size() > MAX_HEADER_SIZE_BYTES) {
                throw new IOException("HTTP headers are too large");
            }
            if (previous3 == '\r' && previous2 == '\n' && previous1 == '\r' && current == '\n') {
                break;
            }
            previous3 = previous2;
            previous2 = previous1;
            previous1 = current;
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    private String readBody(BufferedInputStream input, Map<String, String> headers) throws IOException {
        byte[] body = StringUtils.containsIgnoreCase(headers.get("transfer-encoding"), "chunked")
                ? readChunkedBody(input)
                : readFixedOrUntilClose(input, headers);
        return new String(body, charset(headers));
    }

    private byte[] readChunkedBody(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String line = readAsciiLine(input);
            int chunkSize = Integer.parseInt(StringUtils.substringBefore(line, ";").trim(), 16);
            if (chunkSize == 0) {
                break;
            }
            int remainingCapacity = MAX_BODY_SIZE_BYTES - body.size();
            if (remainingCapacity <= 0) {
                return body.toByteArray();
            }

            int bytesToRead = Math.min(chunkSize, remainingCapacity);
            readUpToLimit(input, body, bytesToRead);
            if (chunkSize > bytesToRead) {
                return body.toByteArray();
            }
            readAsciiLine(input);
        }
        return body.toByteArray();
    }

    private byte[] readFixedOrUntilClose(BufferedInputStream input, Map<String, String> headers) throws IOException {
        int contentLength = parseContentLength(headers.get("content-length"));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        readUpToLimit(input, body, contentLength >= 0 ? contentLength : MAX_BODY_SIZE_BYTES);
        return body.toByteArray();
    }

    private void readUpToLimit(BufferedInputStream input, ByteArrayOutputStream output, int requestedBytes) throws IOException {
        byte[] buffer = new byte[8192];
        int remaining = Math.min(requestedBytes, MAX_BODY_SIZE_BYTES - output.size());
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) {
                return;
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private String readAsciiLine(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int current;
        while ((current = input.read()) != -1) {
            if (current == '\n') {
                break;
            }
            if (current != '\r') {
                line.write(current);
                if (line.size() > MAX_ASCII_LINE_SIZE_BYTES) {
                    throw new IOException("HTTP chunk line is too large");
                }
            }
        }
        return line.toString(StandardCharsets.ISO_8859_1);
    }

    private int statusCode(String headerText) {
        String statusLine = StringUtils.substringBefore(headerText, "\r\n");
        return Integer.parseInt(StringUtils.substringBetween(statusLine, " ", " "));
    }

    private Map<String, String> headers(String headerText) {
        return Arrays.stream(headerText.split("\\r?\\n"))
                .skip(1)
                .map(line -> line.split(":", 2))
                .filter(parts -> parts.length == 2)
                .collect(toMap(
                        parts -> parts[0].trim().toLowerCase(Locale.ROOT),
                        parts -> parts[1].trim(),
                        (first, ignored) -> first
                ));
    }

    private Charset charset(Map<String, String> headers) {
        String contentType = headers.get("content-type");
        String charset = StringUtils.substringAfter(StringUtils.defaultString(contentType), "charset=");
        try {
            return StringUtils.isBlank(charset) ? StandardCharsets.UTF_8 : Charset.forName(charset.trim());
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private int parseContentLength(String value) {
        try {
            return StringUtils.isBlank(value) ? -1 : Integer.parseInt(value);
        } catch (Exception e) {
            return -1;
        }
    }

    private String resolveRedirect(URI baseUri, String location) {
        return baseUri.resolve(location).toString();
    }

    private String requestTarget(URI uri) {
        String path = StringUtils.defaultIfBlank(uri.getRawPath(), "/");
        return StringUtils.isBlank(uri.getRawQuery()) ? path : "%s?%s".formatted(path, uri.getRawQuery());
    }

    private String hostHeader(FetchTarget target) {
        boolean defaultPort = (target.secure() && target.port() == HTTPS_PORT) || (!target.secure() && target.port() == HTTP_PORT);
        return defaultPort ? target.host() : "%s:%d".formatted(target.host(), target.port());
    }

    private boolean isAllowedPort(URI uri) {
        int port = resolvePort(uri);
        return port == HTTP_PORT || port == HTTPS_PORT;
    }

    private int resolvePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return StringUtils.equalsIgnoreCase(uri.getScheme(), "https") ? HTTPS_PORT : HTTP_PORT;
    }

    private boolean isBlockedAddress(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isCarrierGradeNat(address)
                || isUniqueLocalIpv6(address);
    }

    private boolean isCarrierGradeNat(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            return false;
        }

        byte[] bytes = address.getAddress();
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 100 && second >= 64 && second <= 127;
    }

    private boolean isUniqueLocalIpv6(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }

        byte[] bytes = address.getAddress();
        return (bytes[0] & 0xfe) == 0xfc;
    }

    private boolean isIpLiteral(String host) {
        return StringUtils.contains(host, ":") || host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    private boolean shouldStop(BooleanSupplier isCancelled) {
        return Thread.currentThread().isInterrupted() || (isCancelled != null && isCancelled.getAsBoolean());
    }

    private void clean(Document document) {
        document.select("script, style, noscript, svg, canvas, nav, header, footer, aside, form, iframe").remove();
        for (Element element : document.select("[hidden], [aria-hidden=true]")) {
            element.remove();
        }
    }

    private String normalizeText(String text) {
        return StringUtils.defaultString(text).replaceAll("\\s+", " ").trim();
    }

    private String cap(String value) {
        String normalized = normalizeText(value);
        if (normalized.length() <= EXCERPT_LIMIT) {
            return normalized;
        }
        return "%s…".formatted(normalized.substring(0, EXCERPT_LIMIT).trim());
    }

    private BrowsedPage failed(String url, String error) {
        return new BrowsedPage(domain(url), StringUtils.defaultString(url), domain(url), "", false, error);
    }

    private String domain(String url) {
        try {
            return StringUtils.defaultIfBlank(URI.create(StringUtils.defaultString(url)).getHost(), StringUtils.defaultString(url));
        } catch (Exception e) {
            return StringUtils.defaultString(url);
        }
    }

    private record FetchTarget(URI uri, InetAddress address, String host, int port, boolean secure) {
    }

    private record FetchResponse(int statusCode, String location, String body) {
        private boolean isRedirect() {
            return statusCode >= 300 && statusCode < 400 && StringUtils.isNotBlank(location);
        }
    }
}
