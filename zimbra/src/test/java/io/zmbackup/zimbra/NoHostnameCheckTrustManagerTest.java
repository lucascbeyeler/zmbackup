package io.zmbackup.zimbra;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

class NoHostnameCheckTrustManagerTest {

    @Test
    void checkClientTrustedOverloadsAllDelegateToTheTwoArgumentMethod() throws CertificateException {
        RecordingTrustManager delegate = new RecordingTrustManager();
        ZimbraRestMailboxExporter.NoHostnameCheckTrustManager trustManager =
                new ZimbraRestMailboxExporter.NoHostnameCheckTrustManager(delegate);
        X509Certificate[] chain = new X509Certificate[0];

        trustManager.checkClientTrusted(chain, "RSA");
        trustManager.checkClientTrusted(chain, "RSA", (java.net.Socket) null);
        trustManager.checkClientTrusted(chain, "RSA", (javax.net.ssl.SSLEngine) null);

        assertEquals(3, delegate.clientTrustedCalls.size());
        for (Object[] call : delegate.clientTrustedCalls) {
            assertSame(chain, call[0]);
            assertEquals("RSA", call[1]);
        }
    }

    @Test
    void checkServerTrustedOverloadsAllDelegateToTheTwoArgumentMethod() throws CertificateException {
        RecordingTrustManager delegate = new RecordingTrustManager();
        ZimbraRestMailboxExporter.NoHostnameCheckTrustManager trustManager =
                new ZimbraRestMailboxExporter.NoHostnameCheckTrustManager(delegate);
        X509Certificate[] chain = new X509Certificate[0];

        trustManager.checkServerTrusted(chain, "RSA");
        trustManager.checkServerTrusted(chain, "RSA", (java.net.Socket) null);
        trustManager.checkServerTrusted(chain, "RSA", (javax.net.ssl.SSLEngine) null);

        assertEquals(3, delegate.serverTrustedCalls.size());
        for (Object[] call : delegate.serverTrustedCalls) {
            assertSame(chain, call[0]);
            assertEquals("RSA", call[1]);
        }
    }

    @Test
    void getAcceptedIssuersDelegatesToTheWrappedTrustManager() {
        X509Certificate[] issuers = new X509Certificate[0];
        RecordingTrustManager delegate = new RecordingTrustManager(issuers);
        ZimbraRestMailboxExporter.NoHostnameCheckTrustManager trustManager =
                new ZimbraRestMailboxExporter.NoHostnameCheckTrustManager(delegate);

        assertArrayEquals(issuers, trustManager.getAcceptedIssuers());
    }

    /** Hand-rolled stub since this module has no mocking library on the test classpath. */
    private static final class RecordingTrustManager implements X509TrustManager {
        private final X509Certificate[] acceptedIssuers;
        private final List<Object[]> clientTrustedCalls = new ArrayList<>();
        private final List<Object[]> serverTrustedCalls = new ArrayList<>();

        RecordingTrustManager() {
            this(new X509Certificate[0]);
        }

        RecordingTrustManager(X509Certificate[] acceptedIssuers) {
            this.acceptedIssuers = acceptedIssuers;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            clientTrustedCalls.add(new Object[] {chain, authType});
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            serverTrustedCalls.add(new Object[] {chain, authType});
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return acceptedIssuers;
        }
    }
}
