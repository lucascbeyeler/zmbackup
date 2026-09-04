package io.zmbackup.zimbra;

import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.ExtendedResult;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest;
import com.unboundid.ldif.LDIFException;
import com.unboundid.ldif.LDIFReader;
import com.unboundid.ldif.LDIFWriter;
import com.unboundid.util.ssl.PEMFileTrustManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import io.zmbackup.core.domain.LdapObjectType;
import io.zmbackup.core.port.AccountDiscovery;
import io.zmbackup.core.port.ZimbraLdapExporter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;

/**
 * Connects to Zimbra's LDAP directory via the UnboundID LDAP SDK, mirroring the {@code
 * ldapsearch} invocations in the bash tool's {@code MiscAction.sh} and {@code ParallelAction.sh}.
 */
public class UnboundIdLdapAdapter implements AccountDiscovery, ZimbraLdapExporter {

    /**
     * How long {@link #connect} waits to establish the TCP connection before giving up. Without
     * this, an unresponsive directory would hang the calling {@code Parallel.run} worker
     * indefinitely, mirroring the timeout already applied to {@link
     * io.zmbackup.local.EmailNotifier}'s SMTP connection for the same reason.
     */
    private static final long CONNECT_TIMEOUT_MILLIS = 30_000;

    /**
     * How long a single LDAP operation (bind, search, add, delete) is allowed to take once
     * connected. Generous relative to {@link #CONNECT_TIMEOUT_MILLIS} since {@link
     * #discover(LdapObjectType)} can legitimately take a while to enumerate every object across a
     * large directory; the point is only to bound an otherwise-unbounded hang against a
     * connected-but-unresponsive server.
     */
    private static final long RESPONSE_TIMEOUT_MILLIS = 600_000;

    private final String host;
    private final int port;
    private final String bindDn;
    private final String bindPassword;
    private final boolean startTls;
    private final String caCertificatePath;
    private final boolean trustAllCertificates;
    private final boolean backupInactiveAccounts;

    /**
     * @param url                     the LDAP server URL, e.g. {@code "ldap://127.0.0.1:389"}
     * @param bindDn                  the admin distinguished name used to bind
     * @param bindPassword            the admin password used to bind
     * @param startTls                whether to upgrade the connection with StartTLS before binding
     * @param caCertificatePath       path to a PEM-encoded CA certificate (bundle) used to verify the server's
     *                                certificate during StartTLS negotiation, or {@code null} to fall back to
     *                                the JVM's default trust manager (or, if {@code trustAllCertificates} is
     *                                set, to trusting any certificate)
     * @param trustAllCertificates    whether to accept any server certificate during StartTLS negotiation when
     *                                {@code caCertificatePath} is not set; must be explicitly enabled, e.g. for
     *                                self-signed Zimbra certificates in dev/test environments, since it offers
     *                                no protection against an active MITM attack
     * @param backupInactiveAccounts  whether {@link LdapObjectType#ACCOUNT} discovery includes disabled
     *                                accounts, mirroring the bash tool's {@code BACKUP_INACTIVE_ACCOUNTS}:
     *                                when {@code false}, discovery is narrowed to {@code zimbraAccountStatus=active}
     */
    public UnboundIdLdapAdapter(
            String url,
            String bindDn,
            String bindPassword,
            boolean startTls,
            String caCertificatePath,
            boolean trustAllCertificates,
            boolean backupInactiveAccounts) {
        LDAPURL parsedUrl;
        try {
            parsedUrl = new LDAPURL(url);
        } catch (LDAPException e) {
            throw new IllegalArgumentException("Invalid LDAP URL: " + url, e);
        }
        this.host = parsedUrl.getHost();
        this.port = parsedUrl.getPort();
        this.bindDn = bindDn;
        this.bindPassword = bindPassword;
        this.startTls = startTls;
        this.caCertificatePath = caCertificatePath;
        this.trustAllCertificates = trustAllCertificates;
        this.backupInactiveAccounts = backupInactiveAccounts;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mirrors {@code build_listBKP}'s whole-directory search in the bash tool: {@code
     * ldapsearch -b '' <objectFilter> <attributeName>}.
     */
    @Override
    public List<String> discover(LdapObjectType type) throws IOException {
        return search("", type);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mirrors {@code build_listBKP}'s per-domain search in the bash tool: {@code ldapsearch -b
     * "dc=<label>,dc=<label>,..." <objectFilter> <attributeName>}, with the base DN built from the
     * domain's dot-separated labels.
     */
    @Override
    public List<String> discoverForDomain(LdapObjectType type, String domain) throws IOException {
        return search(domainBaseDn(domain), type);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mirrors {@code ldap_backup} in the bash tool's {@code ParallelAction.sh}: {@code
     * ldapsearch -b '' -LLL "(&(|(mail=<identifier>)(uid=<identifier>))<objectFilter>)"}, with the
     * matching entries serialised to LDIF.
     *
     * <p>Domain entries have no {@code mail}/{@code uid} attribute to match against, so {@link
     * LdapObjectType#DOMAIN} is not supported here; domain export has its own base-scoped search.
     */
    @Override
    public void export(String identifier, LdapObjectType type, OutputStream destination) throws IOException {
        if (type == LdapObjectType.DOMAIN) {
            throw new UnsupportedOperationException(
                    "UnboundIdLdapAdapter.export() does not support LdapObjectType.DOMAIN");
        }
        Filter filter;
        try {
            filter = Filter.createANDFilter(
                    Filter.createORFilter(
                            Filter.createEqualityFilter("mail", identifier),
                            Filter.createEqualityFilter("uid", identifier)),
                    Filter.create(type.objectFilter()));
        } catch (LDAPException e) {
            throw new IOException("Invalid LDAP filter for object type " + type, e);
        }
        try (LDAPConnection connection = connect()) {
            SearchRequest searchRequest = new SearchRequest("", SearchScope.SUB, filter);
            SearchResult searchResult = connection.search(searchRequest);
            LDIFWriter ldifWriter = new LDIFWriter(destination);
            for (Entry entry : searchResult.getSearchEntries()) {
                ldifWriter.writeEntry(entry);
            }
            ldifWriter.flush();
        } catch (LDAPException e) {
            throw new IOException(
                    "Failed to export " + identifier + " from Zimbra LDAP at " + host + ":" + port, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mirrors {@code domain_backup} in the bash tool's {@code ParallelAction.sh}: {@code
     * ldapsearch -b "dc=<label>,dc=<label>,..." -s base <objectFilter>}, with the matching entry
     * serialised to LDIF.
     */
    @Override
    public void exportDomain(String domain, OutputStream destination) throws IOException {
        try (LDAPConnection connection = connect()) {
            SearchRequest searchRequest = new SearchRequest(
                    domainBaseDn(domain), SearchScope.BASE, LdapObjectType.DOMAIN.objectFilter());
            SearchResult searchResult = connection.search(searchRequest);
            LDIFWriter ldifWriter = new LDIFWriter(destination);
            for (Entry entry : searchResult.getSearchEntries()) {
                ldifWriter.writeEntry(entry);
            }
            ldifWriter.flush();
        } catch (LDAPException e) {
            throw new IOException(
                    "Failed to export domain " + domain + " from Zimbra LDAP at " + host + ":" + port, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mirrors {@code ldap_restore} in the bash tool's {@code ParallelAction.sh}: {@code
     * ldapdelete -Z -r ...} on the entry's DN (result discarded, exactly as the bash tool does),
     * followed by {@code ldapadd -Z ...} to re-add it from the LDIF. The DN is read from the LDIF
     * itself, so {@code type} is not otherwise used here.
     */
    @Override
    public void restore(LdapObjectType type, InputStream source) throws IOException {
        Entry entry = readEntry(source);
        try (LDAPConnection connection = connect()) {
            deleteRecursively(connection, entry.getDN());
            connection.add(entry);
        } catch (LDAPException e) {
            throw new IOException(
                    "Failed to restore " + entry.getDN() + " to Zimbra LDAP at " + host + ":" + port, e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Mirrors {@code domain_restore} in the bash tool's {@code ParallelAction.sh}: {@code
     * ldapadd -Z ...} only (no preceding delete), treating a {@code ldapadd} "Already exists"
     * failure as success.
     */
    @Override
    public void restoreDomain(InputStream source) throws IOException {
        Entry entry = readEntry(source);
        try (LDAPConnection connection = connect()) {
            connection.add(entry);
        } catch (LDAPException e) {
            if (e.getResultCode() == ResultCode.ENTRY_ALREADY_EXISTS) {
                return;
            }
            throw new IOException(
                    "Failed to restore domain " + entry.getDN() + " to Zimbra LDAP at " + host + ":" + port, e);
        }
    }

    private static Entry readEntry(InputStream source) throws IOException {
        try (LDIFReader ldifReader = new LDIFReader(source)) {
            Entry entry = ldifReader.readEntry();
            if (entry == null) {
                throw new IOException("No LDAP entry found in restore source");
            }
            return entry;
        } catch (LDIFException e) {
            throw new IOException("Invalid LDIF content in restore source", e);
        }
    }

    /**
     * Best-effort recursive delete of {@code dn} and its children, mirroring {@code ldapdelete
     * -r}: the bash tool discards this command's result entirely (redirected to {@code
     * /dev/null}), relying on the following {@code ldapadd} to report any real failure, so
     * failures here are swallowed the same way.
     */
    private static void deleteRecursively(LDAPConnection connection, String dn) {
        try {
            SearchResult children =
                    connection.search(dn, SearchScope.ONE, Filter.createPresenceFilter("objectClass"));
            for (SearchResultEntry child : children.getSearchEntries()) {
                deleteRecursively(connection, child.getDN());
            }
            connection.delete(dn);
        } catch (LDAPException ignored) {
            // Best-effort, exactly like the bash tool's `ldapdelete -r ... > /dev/null 2>&1`.
        }
    }

    /**
     * {@code type}'s object filter, narrowed to active accounts only when {@code type} is
     * {@link LdapObjectType#ACCOUNT} and {@link #backupInactiveAccounts} is disabled - mirroring
     * the bash tool's {@code constant()}, which swaps {@code ACOBJECT} to {@code
     * (&(objectclass=zimbraAccount)(zimbraAccountStatus=active))} when {@code
     * BACKUP_INACTIVE_ACCOUNTS} is not {@code true}.
     */
    private String searchFilter(LdapObjectType type) {
        if (type == LdapObjectType.ACCOUNT && !backupInactiveAccounts) {
            return "(&" + type.objectFilter() + "(zimbraAccountStatus=active))";
        }
        return type.objectFilter();
    }

    private static String domainBaseDn(String domain) {
        StringBuilder baseDn = new StringBuilder();
        for (String label : domain.split("\\.")) {
            if (baseDn.length() > 0) {
                baseDn.append(',');
            }
            baseDn.append("dc=").append(label);
        }
        return baseDn.toString();
    }

    private List<String> search(String baseDn, LdapObjectType type) throws IOException {
        try (LDAPConnection connection = connect()) {
            SearchRequest searchRequest =
                    new SearchRequest(baseDn, SearchScope.SUB, searchFilter(type), type.attributeName());
            SearchResult searchResult = connection.search(searchRequest);
            List<String> values = new ArrayList<>();
            for (Entry entry : searchResult.getSearchEntries()) {
                String value = entry.getAttributeValue(type.attributeName());
                if (value != null) {
                    values.add(value);
                }
            }
            return values;
        } catch (LDAPException e) {
            throw new IOException("Failed to search Zimbra LDAP at " + host + ":" + port, e);
        }
    }

    /**
     * Opens a new connection to the configured server, upgrading it with StartTLS first when
     * configured, then binds as the admin account. Callers are responsible for closing the
     * returned connection.
     */
    LDAPConnection connect() throws IOException {
        LDAPConnection connection = null;
        try {
            LDAPConnectionOptions options = new LDAPConnectionOptions();
            options.setConnectTimeoutMillis((int) CONNECT_TIMEOUT_MILLIS);
            options.setResponseTimeoutMillis(RESPONSE_TIMEOUT_MILLIS);
            connection = new LDAPConnection(options, host, port);
            if (startTls) {
                SSLContext sslContext = startTlsSslContext();
                ExtendedResult startTlsResult =
                        connection.processExtendedOperation(new StartTLSExtendedRequest(sslContext));
                if (startTlsResult.getResultCode() != ResultCode.SUCCESS) {
                    throw new LDAPException(
                            startTlsResult.getResultCode(),
                            "StartTLS negotiation failed: " + startTlsResult.getDiagnosticMessage());
                }
            }
            connection.bind(bindDn, bindPassword);
            return connection;
        } catch (LDAPException | GeneralSecurityException e) {
            if (connection != null) {
                connection.close();
            }
            throw new IOException("Failed to connect to Zimbra LDAP at " + host + ":" + port, e);
        }
    }

    /**
     * Builds the {@link SSLContext} used to verify the server's certificate during StartTLS
     * negotiation: a configured CA certificate takes precedence, then an explicit opt-in to trust
     * any certificate (e.g. for self-signed Zimbra certs in dev/test environments), and otherwise
     * the JVM's default trust manager, which validates against real CAs and protects against an
     * active MITM attack.
     */
    private SSLContext startTlsSslContext() throws GeneralSecurityException {
        if (caCertificatePath != null) {
            return new SSLUtil(new PEMFileTrustManager(new File(caCertificatePath))).createSSLContext();
        }
        if (trustAllCertificates) {
            return new SSLUtil(new TrustAllTrustManager()).createSSLContext();
        }
        return new SSLUtil().createSSLContext();
    }
}
