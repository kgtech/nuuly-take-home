package com.kgtech.inventoryapi.inventory.web;

import java.io.IOException;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Counts the body bytes as they are read and fails past the cap (review R-01): a Content-Length check alone lets a
 * chunked body grow without bound, and a v2 body is materialised (a list of strings) before the record's own limits
 * run. The failure is an IOException, which the JSON reader surfaces as an unreadable message (400).
 */
final class CappedBodyRequest extends HttpServletRequestWrapper {

    private final long cap;

    CappedBodyRequest(HttpServletRequest request, long cap) {
        super(request);
        this.cap = cap;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        ServletInputStream in = super.getInputStream();
        return new ServletInputStream() {
            private long read;

            private void count(long n) throws IOException {
                if (n > 0 && (read += n) > cap) {
                    throw new BodyTooLargeException(cap);
                }
            }

            @Override
            public int read() throws IOException {
                int b = in.read();
                count(b < 0 ? 0 : 1);
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                int n = in.read(buffer, offset, length);
                count(n);
                return n;
            }

            @Override
            public boolean isFinished() {
                return in.isFinished();
            }

            @Override
            public boolean isReady() {
                return in.isReady();
            }

            @Override
            public void setReadListener(ReadListener listener) {
                in.setReadListener(listener);
            }
        };
    }
}
