package io.zmbackup.zimbra;

import io.zmbackup.core.port.ZimbraMailboxExporter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

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
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        this.baseUri = URI.create(baseUrl);
        this.adminUser = Objects.requireNonNull(adminUser, "adminUser must not be null");
        this.adminPassword = Objects.requireNonNull(adminPassword, "adminPassword must not be null");
        // WireMock (used in tests) and most Zimbra REST deployments only speak HTTP/1.1; pinning
        // the version avoids the client's default h2 upgrade attempt hitting an RST_STREAM/EOF on
        // unknown-length POST bodies (see restore()).
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
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
