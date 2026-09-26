package com.kgtech.inventoryapi.inventory.web;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ErrorReportValve;
import org.apache.coyote.ActionCode;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

/**
 * Replaces Tomcat's host ErrorReportValve (C1): an error Spring MVC never sees, such as a request Tomcat rejects before
 * routing, gets a text/plain body from {@link TextErrors#textFor} (400 → "Invalid request", other statuses → the
 * reason phrase, T3, S5). Never writes HTML, exception details or server info, and never overwrites a body already
 * written or an error page already rendered.
 */
final class TextErrorReportValve extends ErrorReportValve {

    @Override
    protected void report(Request request, Response response, Throwable throwable) {
        int status = response.getStatus();
        // Same guards as ErrorReportValve: only unreported errors with nothing written yet, and only while I/O works.
        if (status < 400 || response.getContentWritten() > 0 || !response.setErrorReported()) {
            return;
        }
        AtomicBoolean ioAllowed = new AtomicBoolean(false);
        response.getCoyoteResponse().action(ActionCode.IS_IO_ALLOWED, ioAllowed);
        if (!ioAllowed.get()) {
            return;
        }
        try {
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            Writer writer = response.getReporter();
            if (writer != null) {
                writer.write(TextErrors.textFor(HttpStatusCode.valueOf(status)));
                response.finishResponse();
            }
        } catch (IOException | IllegalStateException e) {
            // The client is gone or the response is committed: nothing more can be sent, as in ErrorReportValve.
        }
    }
}
