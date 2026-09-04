package io.zmbackup.zimbra;

import com.unboundid.util.ssl.PEMFileTrustManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import io.zmbackup.core.port.ZimbraMailboxExporter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Exports a single account's mailbox content as a {@code .tgz} archive over Zimbra's REST
 * interface, mirroring {@code mailbox_backup} in the bash tool's {@code ParallelAction.sh}: {@code
 * getRestURL "/?fmt=tgz&resolve=skip[&query=after:"<date>"]"}.
 *
 * <p>Uses the JDK's {@link HttpClient} directly, with an HTTP Basic {@code Authorization} header
 * built from the configured admin credentials.
 */
public class ZimbraRestMailboxExporter implements ZimbraMailboxExporter {

    /**
     * Matches the bash tool's {@code date +%m/%d/%Y} formatting of the incremental cutoff passed
     * to Zimbra's {@code query=after:"<date>"} REST parameter.
     */
    private static final DateTimeFormatter AFTER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy").withZone(ZoneId.systemDefault());

    /**
     * Zimbra account identifiers are always email addresses. Enforcing that shape here (rather
     * than relying solely on CLI-layer validation) guards {@link #restUri} against identifiers
     * from unvalidated sources, e.g. LDAP discovery, that contain {@code /}, {@code ?}, or {@code
     * #} and could otherwise redirect the REST request to an unintended path or query.
     */
    private static final Pattern ACCOUNT_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    /**
     * How long {@link #httpClient} waits to establish the TCP/TLS connection before giving up.
     * Without this, a Zimbra host that accepts the connection but never responds would hang
     * {@link #export}/{@link #restore} - and, since a single account's export/restore runs
     * synchronously inside a {@code Parallel.run} worker, that worker - indefinitely.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * How long a single export/restore request is allowed to run end-to-end, once connected.
     * Generous relative to {@link #CONNECT_TIMEOUT} since a mailbox archive can legitimately take
     * a long time to transfer; the point is only to bound an otherwise-unbounded hang against a
     * connected-but-unresponsive peer.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofHours(6);

    private final URI baseUri;
    private final String adminUser;
    private final String adminPassword;
    private final HttpClient httpClient;

    /**
     * @param baseUrl       the Zimbra server's REST base URL, e.g. {@code
     *                      "https://mail.example.com:7071"}
     * @param adminUser     the Zimbra admin account used for HTTP Basic authentication
     * @param adminPassword the admin account's password
     */
    public ZimbraRestMailboxExporter(String baseUrl, String adminUser, String adminPassword) {
        this(baseUrl, adminUser, adminPassword, null, false);
    }

    /**
     * @param baseUrl               the Zimbra server's REST base URL, e.g. {@code
     *                              "https://mail.example.com:7071"}
     * @param adminUser             the Zimbra admin account used for HTTP Basic authentication
     * @param adminPassword         the admin account's password
     * @param caCertificatePath     path to a PEM-encoded CA certificate (bundle) used to verify
     *                              the server's certificate, or {@code null} to fall back to the
     *                              JVM's default trust manager (or, if {@code
     *                              trustAllCertificates} is set, to trusting any certificate)
     * @param trustAllCertificates  whether to accept any server certificate when {@code
     *                              caCertificatePath} is not set; must be explicitly enabled, e.g.
     *                              for self-signed Zimbra certificates in dev/test environments,
     *                              since it offers no protection against an active MITM attack
     */
    public ZimbraRestMailboxExporter(
            String baseUrl,
            String adminUser,
            String adminPassword,
            String caCertificatePath,
            boolean trustAllCertificates) {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.baseUri = URI.create(baseUrl);
        this.adminUser = Objects.requireNonNull(adminUser, "adminUser must not be null");
        this.adminPassword = Objects.requireNonNull(adminPassword, "adminPassword must not be null");
        // WireMock (used in tests) and most Zimbra REST deployments only speak HTTP/1.1; pinning
        // the version avoids the client's default h2 upgrade attempt hitting an RST_STREAM/EOF on
        // unknown-length POST bodies (see restore()).
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                // restore()'s request body is a single-use InputStream wrapped in
                // BodyPublishers.ofInputStream(() -> source): if the client ever resent the
                // request (e.g. following a redirect), it would replay that same
                // already-exhausted stream and silently POST an empty body, truncating the
                // restore. HttpClient.Redirect.NEVER is already the JDK default, but pinning it
                // explicitly turns "the client happens not to resend" into a guarantee this class
                // relies on, rather than something a future default change could silently break.
                .followRedirects(HttpClient.Redirect.NEVER);
        SSLContext sslContext = restSslContext(caCertificatePath, trustAllCertificates);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.httpClient = builder.build();
    }

    /**
     * Builds the {@link SSLContext} used to verify the server's certificate, mirroring {@link
     * io.zmbackup.zimbra.UnboundIdLdapAdapter}'s own {@code startTlsSslContext()}: a configured CA
     * certificate takes precedence, then an explicit opt-in to trust any certificate (e.g. for
     * self-signed Zimbra certs in dev/test environments). Returns {@code null} when neither is
     * set, so the caller leaves {@link HttpClient.Builder#sslContext} unset and the JVM's default
     * trust manager - which validates against real CAs and protects against an active MITM attack
     * - applies instead.
     */
    private static SSLContext restSslContext(String caCertificatePath, boolean trustAllCertificates) {
        try {
            if (caCertificatePath != null) {
                return new SSLUtil(new PEMFileTrustManager(new File(caCertificatePath))).createSSLContext();
            }
            if (trustAllCertificates) {
                return new SSLUtil(new NoHostnameCheckTrustManager(new TrustAllTrustManager())).createSSLContext();
            }
            return null;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Failed to build SSL context for Zimbra REST client", e);
        }
    }

    /**
     * Wraps a plain {@link X509TrustManager} as an {@link X509ExtendedTrustManager}, working
     * around a JDK compatibility behavior that would otherwise defeat {@link
     * TrustAllTrustManager}'s purpose here: since JDK 8u31/9, {@link HttpClient} (like other JSSE
     * consumers) automatically performs its own hostname verification against the peer certificate
     * whenever the configured trust manager is a legacy {@link X509TrustManager} rather than an
     * {@link X509ExtendedTrustManager} - a safety net for callers who can't otherwise access
     * connection/hostname context from a plain {@code X509TrustManager}. That safety net is exactly
     * what {@code trustAllCertificates} (mirroring {@code curl -k}: accept any certificate, for a
     * self-signed Zimbra REST cert in dev/test) is opting out of, so without this wrapper the
     * setting would still fail the handshake on a hostname mismatch against such a certificate,
     * silently defeating the point of enabling it. {@link #caCertificatePath}'s {@link
     * PEMFileTrustManager} path is unaffected and keeps real hostname verification: trusting a
     * custom CA is not the same as trusting any hostname.
     */
    private static final class NoHostnameCheckTrustManager extends X509ExtendedTrustManager {
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

    /**
     * {@inheritDoc}
     *
     * <p>Issues an HTTP GET against {@code {baseUrl}/home/{account}/?fmt=tgz&resolve=skip},
     * appending {@code &query=after:"<date>"} when {@code since} is non-null. HTTP 204 (no new
     * content) returns {@code false} without writing to {@code destination}; any other non-200
     * response is reported as an {@link IOException}.
     */
    @Override
    public boolean export(String account, OutputStream destination, Instant since) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(exportUri(account, since))
                .header("Authorization", basicAuthHeader())
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while exporting mailbox for " + account, e);
        }

        try (InputStream body = response.body()) {
            return switch (response.statusCode()) {
                case 200 -> {
                    body.transferTo(destination);
                    yield true;
                }
                case 204 -> false;
                default -> throw new IOException(
                        "Failed to export mailbox for " + account + ": HTTP " + response.statusCode());
            };
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Issues an HTTP POST of {@code source} against {@code
     * {baseUrl}/home/{account}/?fmt=tgz&resolve=skip}, mirroring {@code mailbox_restore} in the
     * bash tool's {@code ParallelAction.sh}: {@code postRestURL '//?fmt=tgz&resolve=skip'
     * <file>.tgz}. {@code account} is whichever account the caller wants the content restored
     * into, which may differ from the account it was originally exported from (restore-on-account).
     */
    @Override
    public void restore(String account, InputStream source) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(restoreUri(account))
                .header("Authorization", basicAuthHeader())
                .timeout(REQUEST_TIMEOUT)
                .POST(BodyPublishers.ofInputStream(() -> source))
                .build();

        HttpResponse<Void> response;
        try {
            response = httpClient.send(request, BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while restoring mailbox for " + account, e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new IOException("Failed to restore mailbox for " + account + ": HTTP " + response.statusCode());
        }
    }

    private URI exportUri(String account, Instant since) throws IOException {
        String query = "fmt=tgz&resolve=skip";
        if (since != null) {
            query += "&query=after:\"" + AFTER_DATE_FORMAT.format(since) + "\"";
        }
        return restUri(account, query);
    }

    private URI restoreUri(String account) throws IOException {
        return restUri(account, "fmt=tgz&resolve=skip");
    }

    private URI restUri(String account, String query) throws IOException {
        if (!ACCOUNT_PATTERN.matcher(account).matches()) {
            throw new IOException("Invalid Zimbra account identifier: " + account);
        }
        String path = (baseUri.getRawPath() == null ? "" : baseUri.getRawPath()) + "/home/" + account + "/";
        try {
            return new URI(baseUri.getScheme(), baseUri.getAuthority(), path, query, null);
        } catch (URISyntaxException e) {
            throw new IOException("Invalid Zimbra REST URL for " + account, e);
        }
    }

    private String basicAuthHeader() {
        String credentials = adminUser + ":" + adminPassword;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
