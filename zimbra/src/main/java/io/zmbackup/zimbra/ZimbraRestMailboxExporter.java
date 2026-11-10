package io.zmbackup.zimbra;

import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import io.zmbackup.core.domain.Identifiers;
import io.zmbackup.core.port.ZimbraMailboxExporter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

public class ZimbraRestMailboxExporter implements ZimbraMailboxExporter {

    private static final DateTimeFormatter AFTER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy").withZone(ZoneId.systemDefault());

    private static final Logger LOG = Logger.getLogger(ZimbraRestMailboxExporter.class.getName());

    private static final Pattern ACCOUNT_PATTERN = Identifiers.EMAIL;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final URI baseUri;
    private final String adminUser;
    private final String adminPassword;
    private final HttpClient httpClient;
    private final MailboxTransferSettings settings;
    private final RestTransport transport;
    private final RestTransport.Retrier retrier;

    public ZimbraRestMailboxExporter(String baseUrl, String adminUser, String adminPassword) {
        this(baseUrl, adminUser, adminPassword, null, false);
    }

    public ZimbraRestMailboxExporter(
            String baseUrl,
            String adminUser,
            String adminPassword,
            String caCertificatePath,
            boolean trustAllCertificates) {
        this(baseUrl, adminUser, adminPassword, caCertificatePath, trustAllCertificates,
                MailboxTransferSettings.defaults());
    }

    public ZimbraRestMailboxExporter(
            String baseUrl,
            String adminUser,
            String adminPassword,
            String caCertificatePath,
            boolean trustAllCertificates,
            MailboxTransferSettings settings) {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.baseUri = URI.create(baseUrl);
        this.adminUser = Objects.requireNonNull(adminUser, "adminUser must not be null");
        this.adminPassword = Objects.requireNonNull(adminPassword, "adminPassword must not be null");
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);
        SSLContext sslContext = restSslContext(caCertificatePath, trustAllCertificates);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.httpClient = builder.build();
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.transport = new HttpTransport();
        this.retrier = new RestTransport.Retrier(settings.retries(), settings.retryBackoff(), LOG);
    }

    private static SSLContext restSslContext(String caCertificatePath, boolean trustAllCertificates) {
        try {
            if (caCertificatePath != null) {
                return new SSLUtil(CaCertificateFile.trustManager("zimbraMailbox.caCertificatePath", caCertificatePath))
                        .createSSLContext();
            }
            if (trustAllCertificates) {
                return new SSLUtil(new NoHostnameCheckTrustManager(new TrustAllTrustManager())).createSSLContext();
            }
            return null;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to build SSL context for Zimbra REST client", e);
        }
    }

    static final class NoHostnameCheckTrustManager extends X509ExtendedTrustManager {
        private final X509TrustManager delegate;

        NoHostnameCheckTrustManager(X509TrustManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            delegate.checkServerTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    @Override
    public boolean export(String account, OutputStream destination, Instant since) throws IOException {
        String query = "fmt=tar";
        if (since != null) {
            query += "&query=after:\"" + AFTER_DATE_FORMAT.format(since) + "\"";
        }
        restUri(account, query);
        if (settings.batchedExport()) {
            String metadataQuery = since == null
                    ? "fmt=tar&body=0"
                    : "fmt=tar&body=0&query=after:\"" + AFTER_DATE_FORMAT.format(since) + "\"";
            return new BatchedMailboxExport(transport, retrier, settings, Clock.systemUTC())
                    .export(account, destination, since, metadataQuery, q -> restUri(account, q));
        }
        URI uri = restUri(account, query);
        try (InputStream body = transport.get(uri, "mailbox export for " + account)) {
            if (body == null) {
                return false;
            }
            body.transferTo(destination);
            destination.flush();
            return true;
        }
    }

    @Override
    public void restore(String account, InputStream source) throws IOException {
        if (settings.batchedRestore()) {
            URI tarUri = restUri(account, "fmt=tar&resolve=skip");
            new BatchedMailboxRestore(transport, retrier).restore(account, source, tarUri);
            return;
        }
        transport.post(
                restUri(account, "fmt=tar&resolve=skip"),
                BodyPublishers.ofInputStream(() -> source),
                "mailbox restore for " + account);
    }

    private final class HttpTransport implements RestTransport {

        @Override
        public InputStream get(URI uri, String description) throws IOException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", basicAuthHeader())
                    .GET()
                    .build();
            HttpResponse.BodyHandler<InputStream> handler = info -> info.statusCode() == 200
                    ? new InactivityTimeoutBodySubscriber(settings.exportInactivityTimeout(), description)
                    : BodySubscribers.replacing(null);
            HttpResponse<InputStream> response =
                    awaitHeaders(httpClient.sendAsync(request, handler), settings.exportHeaderTimeout(), description);
            return switch (response.statusCode()) {
                case 200 -> response.body();
                case 204 -> null;
                default -> throw new HttpStatusException(
                        "Failed " + description + ": HTTP " + response.statusCode(), response.statusCode());
            };
        }

        @Override
        public void post(URI uri, HttpRequest.BodyPublisher body, String description) throws IOException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", basicAuthHeader())
                    .POST(body)
                    .build();
            HttpResponse<Void> response = awaitHeaders(
                    httpClient.sendAsync(request, BodyHandlers.discarding()), settings.restoreTimeout(), description);
            if (response.statusCode() / 100 != 2) {
                throw new HttpStatusException(
                        "Failed " + description + ": HTTP " + response.statusCode(), response.statusCode());
            }
        }

        private <T> HttpResponse<T> awaitHeaders(
                CompletableFuture<HttpResponse<T>> future, Duration deadline, String description) throws IOException {
            try {
                return future.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new HttpTimeoutException("No reply from Zimbra within " + deadline.toSeconds() + " s for "
                        + description + "; the request was cancelled");
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted during " + description);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                IOException failure = cause instanceof IOException io
                        ? io
                        : new IOException("Failed " + description, cause);
                throw enrichIfCertificateFailure(failure);
            }
        }
    }

    private URI restUri(String account, String query) throws IOException {
        if (!ACCOUNT_PATTERN.matcher(account).matches()) {
            throw new IOException("Invalid Zimbra account identifier: " + account);
        }
        String path = (baseUri.getRawPath() == null ? "" : baseUri.getRawPath()) + "/service/home/" + account + "/";
        try {
            return new URI(baseUri.getScheme(), baseUri.getAuthority(), path, query, null);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid Zimbra REST URL for " + account, e);
        }
    }

    private IOException enrichIfCertificateFailure(IOException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CertificateException || cause instanceof SSLHandshakeException) {
                return new IOException(
                        "Failed to reach Zimbra mailbox REST at " + baseUri + ": TLS certificate validation"
                                + " failed. If this server uses a self-signed certificate - the common case when"
                                + " migrating from the 1.2 bash tool, which always trusted it via a JVM-wide"
                                + " truststore import - configure zimbraMailbox.caCertificatePath, or set"
                                + " zimbraMailbox.trustAllCertificates: true (and allowInsecure: true) to opt back"
                                + " into that behavior.",
                        failure);
            }
        }
        return failure;
    }

    private String basicAuthHeader() {
        String credentials = adminUser + ":" + adminPassword;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
