package com.kgtech.inventoryapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.ALLOW;
import static org.springframework.http.HttpHeaders.CONTENT_LENGTH;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.HttpHeaders.TRANSFER_ENCODING;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Sends an HTTP/1.x request to a real server byte for byte and reads the whole response. Tests use it where the
 * request must reach Tomcat exactly as written (escapes MockMvc and java.net.URI would decode or reject, a missing or
 * repeated Host, an oversized header). The caller sends Connection: close so the server ends the response.
 */
public final class RawHttp {

    private static final int TIMEOUT_MILLIS = 30_000;
    private static final String CRLF = "\r\n";

    /** One response: status, headers (case-insensitive) and the body decoded as UTF-8 after de-chunking. */
    public record Response(int status, HttpHeaders headers, String body) {

        /** The parsed Content-Type, or null when the response has none. */
        public MediaType contentType() {
            String value = headers.getFirst(CONTENT_TYPE);
            return value == null ? null : MediaType.parseMediaType(value);
        }

        /** The methods in the Allow header, in any order (Spring does not fix the order), or null without one. */
        public Set<String> allow() {
            String value = headers.getFirst(ALLOW);
            return value == null ? null : Set.copyOf(Arrays.stream(value.split(",")).map(String::trim).toList());
        }
    }

    private RawHttp() {
    }

    /** One header line, terminated by CRLF, for building a request head. */
    public static String header(String name, String value) {
        return name + ": " + value + CRLF;
    }

    /**
     * Writes {@code head} (the request line and header lines, each ending in CRLF), the blank line and {@code body}
     * (may be empty) as ASCII/UTF-8 bytes, then reads until the server closes the connection.
     */
    public static Response send(int port, String head, String body) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write((head + CRLF).getBytes(StandardCharsets.US_ASCII));
            out.write(body.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return parse(socket.getInputStream().readAllBytes());
        }
    }

    private static Response parse(byte[] raw) throws IOException {
        int headerEnd = indexOf(raw, (CRLF + CRLF).getBytes(StandardCharsets.US_ASCII), 0);
        assertThat(headerEnd).as("end of headers in %s", new String(raw, StandardCharsets.ISO_8859_1))
                .isNotNegative();
        String[] lines = new String(raw, 0, headerEnd, StandardCharsets.ISO_8859_1).split(CRLF);
        String[] statusLine = lines[0].split(" ", 3);
        assertThat(statusLine[0]).as(lines[0]).startsWith("HTTP/1.");
        int status = Integer.parseInt(statusLine[1]);
        HttpHeaders headers = new HttpHeaders();
        for (String line : Arrays.asList(lines).subList(1, lines.length)) {
            int colon = line.indexOf(':');
            headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        byte[] body = Arrays.copyOfRange(raw, headerEnd + 4, raw.length);
        if ("chunked".equalsIgnoreCase(headers.getFirst(TRANSFER_ENCODING))) {
            body = dechunk(body);
        } else if (headers.containsHeader(CONTENT_LENGTH)) {
            int length = Integer.parseInt(headers.getFirst(CONTENT_LENGTH));
            assertThat(body.length).as("body bytes").isGreaterThanOrEqualTo(length);
            body = Arrays.copyOf(body, length);
        }
        return new Response(status, headers, new String(body, StandardCharsets.UTF_8));
    }

    private static byte[] dechunk(byte[] chunked) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] crlf = CRLF.getBytes(StandardCharsets.US_ASCII);
        int pos = 0;
        while (true) {
            int lineEnd = indexOf(chunked, crlf, pos);
            assertThat(lineEnd).as("chunk size line").isNotNegative();
            String sizeLine = new String(chunked, pos, lineEnd - pos, StandardCharsets.US_ASCII);
            int size = Integer.parseInt(sizeLine.split(";", 2)[0].trim(), 16);
            if (size == 0) {
                return out.toByteArray();
            }
            out.write(chunked, lineEnd + 2, size);
            pos = lineEnd + 2 + size + 2;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        for (int i = from; i <= haystack.length - needle.length; i++) {
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
                return i;
            }
        }
        return -1;
    }
}
