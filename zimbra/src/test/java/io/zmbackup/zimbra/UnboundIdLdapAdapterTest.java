package io.zmbackup.zimbra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.util.ObjectPair;
import com.unboundid.util.ssl.KeyStoreKeyManager;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import com.unboundid.util.ssl.cert.PublicKeyAlgorithmIdentifier;
import com.unboundid.util.ssl.cert.SignatureAlgorithmIdentifier;
import com.unboundid.util.ssl.cert.X509Certificate;
import io.zmbackup.core.domain.LdapObjectType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.List;
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

    @Test
    void discoverReturnsIdentifyingAttributeForMatchingEntries() throws Exception {
        directoryServer = startDirectoryServer(null);
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"));
        directoryServer.add(
                "uid=bob,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "bob"),
                new Attribute("zimbraMailDeliveryAddress", "bob@example.com"));
        directoryServer.add(
                "cn=engineering,dc=example,dc=com",
                new Attribute("objectClass", "zimbraDistributionList"),
                new Attribute("cn", "engineering"),
                new Attribute("mail", "engineering@example.com"));
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        List<String> accounts = adapter.discover(LdapObjectType.ACCOUNT);

        assertEquals(List.of("alice@example.com", "bob@example.com"), accounts);
    }

    @Test
    void discoverReturnsEmptyListWhenNoEntriesMatch() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        assertEquals(List.of(), adapter.discover(LdapObjectType.ACCOUNT));
    }

    @Test
    void discoverForDomainReturnsOnlyEntriesUnderThatDomain() throws Exception {
        directoryServer = startDirectoryServer(null);
        directoryServer.add(
                "dc=other,dc=com", new Attribute("objectClass", "domain"), new Attribute("dc", "other"));
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"));
        directoryServer.add(
                "uid=carol,dc=other,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "carol"),
                new Attribute("zimbraMailDeliveryAddress", "carol@other.com"));
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        List<String> accounts = adapter.discoverForDomain(LdapObjectType.ACCOUNT, "example.com");

        assertEquals(List.of("alice@example.com"), accounts);
    }

    @Test
    void discoverForDomainReturnsEmptyListWhenDomainHasNoMatches() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        assertEquals(List.of(), adapter.discoverForDomain(LdapObjectType.ACCOUNT, "example.com"));
    }

    @Test
    void discoverForDomainWrapsUnknownBaseDnInIOException() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        assertThrows(IOException.class, () -> adapter.discoverForDomain(LdapObjectType.ACCOUNT, "nowhere.invalid"));
    }

    @Test
    void listDomainsReturnsEveryDomainInTheDirectory() throws Exception {
        directoryServer = startDirectoryServer(null);
        directoryServer.add(
                "dc=other,dc=com",
                new Attribute("objectClass", "zimbraDomain"),
                new Attribute("dc", "other"),
                new Attribute("zimbraDomainName", "other.com"));
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"));
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        List<String> domains = adapter.listDomains();

        assertEquals(List.of("other.com"), domains);
    }

    @Test
    void listDomainsReturnsEmptyListWhenNoDomainsMatch() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        assertEquals(List.of(), adapter.listDomains());
    }

    @Test
    void exportWritesMatchingEntryAsLdif() throws Exception {
        directoryServer = startDirectoryServer(null);
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"));
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        adapter.export("alice@example.com", LdapObjectType.ACCOUNT, destination);

        String ldif = destination.toString();
        assertTrue(ldif.contains("dn: uid=alice,dc=example,dc=com"));
        assertTrue(ldif.contains("mail: alice@example.com"));
    }

    @Test
    void exportMatchesByUidWhenMailAttributeIsAbsent() throws Exception {
        directoryServer = startDirectoryServer(null);
        directoryServer.add(
                "cn=engineering,dc=example,dc=com",
                new Attribute("objectClass", "zimbraDistributionList"),
                new Attribute("cn", "engineering"),
                new Attribute("uid", "engineering@example.com"));
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        adapter.export("engineering@example.com", LdapObjectType.DISTRIBUTION_LIST, destination);

        assertTrue(destination.toString().contains("dn: cn=engineering,dc=example,dc=com"));
    }

    @Test
    void exportWritesNothingWhenNoEntryMatches() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        adapter.export("nobody@example.com", LdapObjectType.ACCOUNT, destination);

        assertEquals("", destination.toString());
    }

    @Test
    void exportRejectsDomainObjectType() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        assertThrows(
                UnsupportedOperationException.class,
                () -> adapter.export("example.com", LdapObjectType.DOMAIN, new ByteArrayOutputStream()));
    }

    @Test
    void exportWrapsUnreachableServerInIOException() {
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter("ldap://127.0.0.1:1", BIND_DN, BIND_PASSWORD, false);

        assertThrows(
                IOException.class,
                () -> adapter.export("alice@example.com", LdapObjectType.ACCOUNT, new ByteArrayOutputStream()));
    }

    @Test
    void exportDomainWritesMatchingEntryAsLdif() throws Exception {
        directoryServer = startDirectoryServer(null);
        directoryServer.add(
                "dc=other,dc=com",
                new Attribute("objectClass", "zimbraDomain"),
                new Attribute("dc", "other"),
                new Attribute("zimbraDomainName", "other.com"));
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        ByteArrayOutputStream destination = new ByteArrayOutputStream();
        adapter.exportDomain("other.com", destination);

        String ldif = destination.toString();
        assertTrue(ldif.contains("dn: dc=other,dc=com"));
        assertTrue(ldif.contains("zimbraDomainName: other.com"));
    }

    @Test
    void exportDomainWrapsUnknownDomainInIOException() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        assertThrows(IOException.class, () -> adapter.exportDomain("nowhere.invalid", new ByteArrayOutputStream()));
    }

    @Test
    void exportDomainWrapsUnreachableServerInIOException() {
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter("ldap://127.0.0.1:1", BIND_DN, BIND_PASSWORD, false);

        assertThrows(
                IOException.class, () -> adapter.exportDomain("example.com", new ByteArrayOutputStream()));
    }

    @Test
    void restoreDeletesExistingEntryAndReAddsFromLdif() throws Exception {
        directoryServer = startDirectoryServer(null);
        directoryServer.add(
                "uid=alice,dc=example,dc=com",
                new Attribute("objectClass", "zimbraAccount"),
                new Attribute("uid", "alice"),
                new Attribute("zimbraMailDeliveryAddress", "alice@example.com"),
                new Attribute("mail", "alice@example.com"),
                new Attribute("description", "stale"));
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);
        String ldif =
                "dn: uid=alice,dc=example,dc=com\n"
                        + "objectClass: zimbraAccount\n"
                        + "uid: alice\n"
                        + "zimbraMailDeliveryAddress: alice@example.com\n"
                        + "mail: alice@example.com\n"
                        + "description: restored\n";

        adapter.restore(LdapObjectType.ACCOUNT, new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8)));

        Entry restored = directoryServer.getEntry("uid=alice,dc=example,dc=com");
        assertEquals("restored", restored.getAttributeValue("description"));
    }

    @Test
    void restoreAddsEntryThatDidNotPreviouslyExist() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);
        String ldif =
                "dn: uid=alice,dc=example,dc=com\n"
                        + "objectClass: zimbraAccount\n"
                        + "uid: alice\n"
                        + "zimbraMailDeliveryAddress: alice@example.com\n";

        adapter.restore(LdapObjectType.ACCOUNT, new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8)));

        Entry added = directoryServer.getEntry("uid=alice,dc=example,dc=com");
        assertEquals("alice@example.com", added.getAttributeValue("zimbraMailDeliveryAddress"));
    }

    @Test
    void restoreThrowsIOExceptionWhenLdifHasNoEntry() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);

        assertThrows(
                IOException.class,
                () -> adapter.restore(LdapObjectType.ACCOUNT, new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void restoreDomainAddsEntryThatDidNotPreviouslyExist() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);
        String ldif =
                "dn: dc=other,dc=com\n"
                        + "objectClass: zimbraDomain\n"
                        + "dc: other\n"
                        + "zimbraDomainName: other.com\n";

        adapter.restoreDomain(new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8)));

        Entry added = directoryServer.getEntry("dc=other,dc=com");
        assertEquals("other.com", added.getAttributeValue("zimbraDomainName"));
    }

    @Test
    void restoreDomainTreatsAlreadyExistingEntryAsSuccess() throws Exception {
        directoryServer = startDirectoryServer(null);
        directoryServer.add(
                "dc=other,dc=com",
                new Attribute("objectClass", "zimbraDomain"),
                new Attribute("dc", "other"),
                new Attribute("zimbraDomainName", "other.com"));
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);
        String ldif =
                "dn: dc=other,dc=com\n"
                        + "objectClass: zimbraDomain\n"
                        + "dc: other\n"
                        + "zimbraDomainName: other.com\n";

        adapter.restoreDomain(new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void restoreDomainThrowsIOExceptionOnOtherFailures() throws Exception {
        directoryServer = startDirectoryServer(null);
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter(
                "ldap://127.0.0.1:" + directoryServer.getListenPort(), BIND_DN, BIND_PASSWORD, false);
        String ldif = "dn: dc=other,dc=nowhere,dc=missing\nobjectClass: zimbraDomain\ndc: other\n";

        assertThrows(
                IOException.class,
                () -> adapter.restoreDomain(new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void restoreWrapsUnreachableServerInIOException() {
        UnboundIdLdapAdapter adapter = new UnboundIdLdapAdapter("ldap://127.0.0.1:1", BIND_DN, BIND_PASSWORD, false);
        String ldif = "dn: uid=alice,dc=example,dc=com\nobjectClass: zimbraAccount\nuid: alice\n";

        assertThrows(
                IOException.class,
                () -> adapter.restore(
                        LdapObjectType.ACCOUNT, new ByteArrayInputStream(ldif.getBytes(StandardCharsets.UTF_8))));
    }

    private InMemoryDirectoryServer startDirectoryServer(SSLSocketFactory startTlsSocketFactory) throws Exception {
        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig("dc=example,dc=com", "dc=other,dc=com");
        config.addAdditionalBindCredentials(BIND_DN, BIND_PASSWORD);
        config.setSchema(null);
        config.setListenerConfigs(
                InMemoryListenerConfig.createLDAPConfig("default", null, 0, startTlsSocketFactory));
        InMemoryDirectoryServer server = new InMemoryDirectoryServer(config);
        server.startListening();
        server.add("dc=example,dc=com", new Attribute("objectClass", "domain"), new Attribute("dc", "example"));
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
