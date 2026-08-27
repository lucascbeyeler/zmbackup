package io.zmbackup.zimbra;

import io.zmbackup.core.port.ZimbraMailboxExporter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Objects;

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
        this.httpClient = HttpClient.newHttpClient();
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
     * <p>Not yet implemented.
     */
    @Override
    public void restore(String account, InputStream source) throws IOException {
        throw new UnsupportedOperationException("ZimbraRestMailboxExporter.restore() is not yet implemented");
    }

    private URI exportUri(String account, Instant since) throws IOException {
        String path = (baseUri.getRawPath() == null ? "" : baseUri.getRawPath()) + "/home/" + account + "/";
        String query = "fmt=tgz&resolve=skip";
        if (since != null) {
            query += "&query=after:\"" + AFTER_DATE_FORMAT.format(since) + "\"";
        }
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
