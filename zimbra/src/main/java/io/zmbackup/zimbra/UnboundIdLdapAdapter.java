package io.zmbackup.zimbra;

import com.unboundid.ldap.sdk.ExtendedResult;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPURL;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.extensions.StartTLSExtendedRequest;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import java.io.IOException;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;

/**
 * Connects to Zimbra's LDAP directory via the UnboundID LDAP SDK, mirroring the {@code
 * ldapsearch} invocations in the bash tool's {@code MiscAction.sh}. Account and domain
 * enumeration is built on top of {@link #connect()} in follow-up work.
 */
public class UnboundIdLdapAdapter {

    private final String host;
    private final int port;
    private final String bindDn;
    private final String bindPassword;
    private final boolean startTls;

    /**
     * @param url          the LDAP server URL, e.g. {@code "ldap://127.0.0.1:389"}
     * @param bindDn       the admin distinguished name used to bind
     * @param bindPassword the admin password used to bind
     * @param startTls     whether to upgrade the connection with StartTLS before binding
     */
    public UnboundIdLdapAdapter(String url, String bindDn, String bindPassword, boolean startTls) {
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
    }

    /**
     * Opens a new connection to the configured server, upgrading it with StartTLS first when
     * configured, then binds as the admin account. Callers are responsible for closing the
     * returned connection.
     */
    LDAPConnection connect() throws IOException {
        LDAPConnection connection = null;
        try {
            connection = new LDAPConnection(host, port);
            if (startTls) {
                // Zimbra's own LDAP server uses a self-signed certificate that isn't in the JVM's
                // default trust store, so trust it the same way the bash tool's ldapsearch does
                // via its default ldaprc (TLS_REQCERT never) rather than requiring a CA bundle.
                SSLContext sslContext = new SSLUtil(new TrustAllTrustManager()).createSSLContext();
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
}
