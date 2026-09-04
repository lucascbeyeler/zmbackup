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
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;

public class UnboundIdLdapAdapter implements AccountDiscovery, ZimbraLdapExporter {

    private static final long CONNECT_TIMEOUT_MILLIS = 30_000;

    private static final long DEFAULT_RESPONSE_TIMEOUT_MILLIS = 600_000;

    private final String host;
    private final int port;
    private final String bindDn;
    private final String bindPassword;
    private final boolean startTls;
    private final String caCertificatePath;
    private final boolean trustAllCertificates;
    private final boolean backupInactiveAccounts;
    private final long responseTimeoutMillis;

    public UnboundIdLdapAdapter(
            String url,
            String bindDn,
            String bindPassword,
            boolean startTls,
            String caCertificatePath,
            boolean trustAllCertificates,
            boolean backupInactiveAccounts) {
        this(
                url,
                bindDn,
                bindPassword,
                startTls,
                caCertificatePath,
                trustAllCertificates,
                backupInactiveAccounts,
                DEFAULT_RESPONSE_TIMEOUT_MILLIS);
    }

    public UnboundIdLdapAdapter(
            String url,
            String bindDn,
            String bindPassword,
            boolean startTls,
            String caCertificatePath,
            boolean trustAllCertificates,
            boolean backupInactiveAccounts,
            long responseTimeoutMillis) {
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
        this.responseTimeoutMillis = responseTimeoutMillis;
        this.backupInactiveAccounts = backupInactiveAccounts;
    }

    @Override
    public List<String> discover(LdapObjectType type) throws IOException {
        return search("", type);
    }

    @Override
    public List<String> discoverForDomain(LdapObjectType type, String domain) throws IOException {
        return search(domainBaseDn(domain), type);
    }

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

    private static void deleteRecursively(LDAPConnection connection, String dn) {
        try {
            SearchResult children =
                    connection.search(dn, SearchScope.ONE, Filter.createPresenceFilter("objectClass"));
            for (SearchResultEntry child : children.getSearchEntries()) {
                deleteRecursively(connection, child.getDN());
            }
            connection.delete(dn);
        } catch (LDAPException ignored) {
        }
    }

    private String searchFilter(LdapObjectType type) {
        if (type == LdapObjectType.ACCOUNT && !backupInactiveAccounts) {
            return "(&" + type.objectFilter() + "(zimbraAccountStatus=active))";
        }
        return type.objectFilter();
    }

    private static final Pattern DOMAIN_PATTERN = Pattern.compile("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    private static String domainBaseDn(String domain) throws IOException {
        if (!DOMAIN_PATTERN.matcher(domain).matches()) {
            throw new IOException("Invalid domain name: " + domain);
        }
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

    LDAPConnection connect() throws IOException {
        LDAPConnection connection = null;
        try {
            LDAPConnectionOptions options = new LDAPConnectionOptions();
            options.setConnectTimeoutMillis((int) CONNECT_TIMEOUT_MILLIS);
            options.setResponseTimeoutMillis(responseTimeoutMillis);
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
