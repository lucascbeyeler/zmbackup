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

public class ZimbraRestMailboxExporter implements ZimbraMailboxExporter {

    private static final DateTimeFormatter AFTER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("MM/dd/yyyy").withZone(ZoneId.systemDefault());

    private static final Pattern ACCOUNT_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private static final Duration REQUEST_TIMEOUT = Duration.ofHours(6);

    private final URI baseUri;
    private final String adminUser;
    private final String adminPassword;
    private final HttpClient httpClient;

    public ZimbraRestMailboxExporter(String baseUrl, String adminUser, String adminPassword) {
        this(baseUrl, adminUser, adminPassword, null, false);
    }

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
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);
        SSLContext sslContext = restSslContext(caCertificatePath, trustAllCertificates);
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        this.httpClient = builder.build();
    }

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
        String path = (baseUri.getRawPath() == null ? "" : baseUri.getRawPath()) + "/service/home/" + account + "/";
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
