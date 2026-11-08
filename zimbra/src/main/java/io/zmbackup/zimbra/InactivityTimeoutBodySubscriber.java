package io.zmbackup.zimbra;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Exposes a response body as an {@link InputStream} that fails when the server sends nothing for
 * longer than the inactivity limit.
 *
 * <p>The JDK's own {@code BodyHandlers.ofInputStream()} behaves differently across versions: on Java
 * 17 a request {@code timeout()} doesn't cover the body at all, so a stalled export blocks forever,
 * while on newer JDKs it caps the whole exchange, so a long healthy export is killed. This subscriber
 * enforces the limit itself, only while the reader is waiting for the server, so a slow destination
 * never counts as server inactivity. On a timeout, or when the stream is closed early, it cancels the
 * subscription, which aborts the exchange and closes the connection.
 */
final class InactivityTimeoutBodySubscriber implements BodySubscriber<InputStream> {

    private static final Object END = new Object();

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final CompletableFuture<Flow.Subscription> subscription = new CompletableFuture<>();
    private final Duration inactivityTimeout;
    private final String description;

    InactivityTimeoutBodySubscriber(Duration inactivityTimeout, String description) {
        this.inactivityTimeout = inactivityTimeout;
        this.description = description;
    }

    @Override
    public CompletionStage<InputStream> getBody() {
        return CompletableFuture.completedFuture(new Stream());
    }

    @Override
    public void onSubscribe(Flow.Subscription s) {
        if (!subscription.complete(s)) {
            s.cancel();
            return;
        }
        s.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
        queue.add(item);
    }

    @Override
    public void onError(Throwable throwable) {
        queue.add(throwable);
    }

    @Override
    public void onComplete() {
        queue.add(END);
    }

    private void cancel() {
        subscription.thenAccept(Flow.Subscription::cancel);
    }

    private final class Stream extends InputStream {
        private Iterator<ByteBuffer> pending;
        private ByteBuffer current;
        private boolean finished;
        private boolean closed;

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("Stream closed");
            }
            if (len == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int n = Math.min(len, current.remaining());
            current.get(b, off, n);
            return n;
        }

        @Override
        public int available() {
            return current == null ? 0 : current.remaining();
        }

        private boolean fill() throws IOException {
            while (current == null || !current.hasRemaining()) {
                if (pending != null && pending.hasNext()) {
                    current = pending.next();
                    continue;
                }
                if (finished) {
                    return false;
                }
                Object next;
                try {
                    next = queue.poll(inactivityTimeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cancel();
                    throw new InterruptedIOException("Interrupted while reading " + description);
                }
                if (next == null) {
                    finished = true;
                    cancel();
                    throw new InactivityTimeoutException("No data received for " + inactivityTimeout.toSeconds()
                            + " s while reading " + description + "; the request was cancelled");
                }
                if (next == END) {
                    finished = true;
                    return false;
                }
                if (next instanceof Throwable failure) {
                    finished = true;
                    throw failure instanceof IOException io ? io : new IOException(
                            "Failed while reading " + description, failure);
                }
                @SuppressWarnings("unchecked")
                List<ByteBuffer> buffers = (List<ByteBuffer>) next;
                pending = buffers.iterator();
                subscription.join().request(1);
            }
            return true;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (!finished) {
                finished = true;
                cancel();
            }
        }
    }

    /** Thrown when the server sends nothing for longer than the inactivity limit. */
    static final class InactivityTimeoutException extends IOException {
        InactivityTimeoutException(String message) {
            super(message);
        }
    }
}
