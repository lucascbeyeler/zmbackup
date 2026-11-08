package io.zmbackup.zimbra;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/** The HTTP calls batched exports and restores make, so they can be retried and tested in isolation. */
interface RestTransport {

    /**
     * Starts a GET and returns its body, watched for inactivity, or {@code null} on {@code 204 No Content}.
     *
     * @throws HttpStatusException on any other status than 200 or 204
     */
    InputStream get(URI uri, String description) throws IOException;

    /** POSTs {@code body} and waits for a 2xx reply within the restore deadline. */
    void post(URI uri, HttpRequest.BodyPublisher body, String description) throws IOException;

    /** A non-success HTTP status. 4xx statuses are not retried. */
    final class HttpStatusException extends IOException {
        private final int status;

        HttpStatusException(String message, int status) {
            super(message);
            this.status = status;
        }

        int status() {
            return status;
        }

        boolean retryable() {
            return status >= 500 || status == 408 || status == 429;
        }
    }

    @FunctionalInterface
    interface IoCall<T> {
        T call() throws IOException;
    }

    /** Runs a call, retrying failed attempts with exponential backoff. */
    final class Retrier {
        private final int retries;
        private final Duration backoff;
        private final String loggerName;

        Retrier(int retries, Duration backoff, Logger log) {
            this.retries = retries;
            this.backoff = backoff;
            this.loggerName = log.getName();
        }

        <T> T run(String description, IoCall<T> call) throws IOException {
            int attempt = 0;
            while (true) {
                try {
                    return call.call();
                } catch (IOException e) {
                    if (attempt >= retries || !retryable(e)) {
                        throw e;
                    }
                    long waitMillis = backoff.toMillis() << Math.min(attempt, 20);
                    attempt++;
                    int thisAttempt = attempt;
                    Logger.getLogger(loggerName).log(Level.WARNING, e, () -> description + " failed ("
                            + e.getMessage() + "); retry " + thisAttempt + " of " + retries + " in " + waitMillis + " ms");
                    try {
                        Thread.sleep(waitMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException("Interrupted while waiting to retry " + description);
                    }
                }
            }
        }

        private static boolean retryable(IOException e) {
            if (e instanceof java.io.InterruptedIOException && Thread.currentThread().isInterrupted()) {
                return false;
            }
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.security.cert.CertificateException
                        || cause instanceof javax.net.ssl.SSLHandshakeException) {
                    return false;
                }
            }
            return !(e instanceof HttpStatusException status) || status.retryable();
        }
    }
}
