package io.zmbackup.zimbra;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.util.ObjectPair;
import com.unboundid.util.ssl.KeyStoreKeyManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import com.unboundid.util.ssl.cert.PublicKeyAlgorithmIdentifier;
import com.unboundid.util.ssl.cert.SignatureAlgorithmIdentifier;
import com.unboundid.util.ssl.cert.X509Certificate;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UnboundIdLdapAdapterTest {

    private static final String BIND_DN = "uid=zimbra,cn=admins,cn=zimbra";
    private static final String BIND_PASSWORD = "secret";

    private InMemoryDirectoryServer directoryServer;
    private Path keyStoreFile;

    @AfterEach
    void tearDown() throws IOException {
        if (directoryServer != null) {
            directoryServer.shutDown(true);
        }
        if (keyStoreFile != null) {
            Files.deleteIfExists(keyStoreFile);
        }
    }

    @Test
    void connectsAndBindsWithoutStartTls() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        try (LDAPConnection connection = adapter.connect()) {
            assertTrue(connection.isConnected());
        }
    }

    @Test
    void connectsAndBindsWithStartTls() throws Exception {
        directoryServer = startDirectoryServer(serverStartTlsSocketFactory());
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, true);

        try (LDAPConnection connection = adapter.connect()) {
            assertTrue(connection.isConnected());
        }
    }

    @Test
    void wrapsInvalidCredentialsInIOException() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, "wrong-password", false);

        assertThrows(IOException.class, adapter::connect);
    }

    @Test
    void wrapsUnreachableServerInIOException() {
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter("ldap://127.0.0.1:1", BIND_DN, BIND_PASSWORD, false);

        assertThrows(IOException.class, adapter::connect);
    }

    @Test
    void rejectsInvalidUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new UnboundIdLdapAdapter("not-a-url", BIND_DN, BIND_PASSWORD, false));
    }

    private InMemoryDirectoryServer startDirectoryServer(SSLSocketFactory startTlsSocketFactory) throws Exception {
        InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig("dc=example,dc=com");
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
        config.setListenerConfigs(
                InMemoryListenerConfig.createLDAPConfig("default", null, 0, startTlsSocketFactory));
        InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
        server.startListening();
        return server;
    }

    /** A throwaway self-signed cert/key so the in-memory server can negotiate StartTLS. */
    private SSLSocketFactory serverStartTlsSocketFactory() throws Exception {
        ObjectPair<X509Certificate, KeyPair> certAndKey = X509Certificate.generateSelfSignedCertificate(
                SignatureAlgorithmIdentifier.SHA_256_WITH_RSA,
                PublicKeyAlgorithmIdentifier.RSA,
                2048,
                new DN("CN=localhost"),
                System.currentTimeMillis(),
                System.currentTimeMillis() + Duration.ofDays(1).toMillis());

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry(
                "server-cert",
                certAndKey.getSecond().getPrivate(),
                BIND_PASSWORD.toCharArray(),
                new Certificate[] {certAndKey.getFirst().toCertificate()});

        keyStoreFile = Files.createTempFile("zmbackup-test-keystore", ".p12");
        try (OutputStream out = Files.newOutputStream(keyStoreFile)) {
            keyStore.store(out, BIND_PASSWORD.toCharArray());
        }

        KeyStoreKeyManager keyManager = new KeyStoreKeyManager(keyStoreFile.toFile(), BIND_PASSWORD.toCharArray());
        return new SSLUtil(keyManager, new TrustAllTrustManager()).createSSLSocketFactory();
    }
}
