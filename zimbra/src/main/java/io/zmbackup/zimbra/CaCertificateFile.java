package io.zmbackup.zimbra;

import com.unboundid.util.ssl.PEMFileTrustManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStoreException;

final class CaCertificateFile {

    private CaCertificateFile() {}

    static PEMFileTrustManager trustManager(String setting, String caCertificatePath) throws KeyStoreException {
        Path path = Path.of(caCertificatePath);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalStateException(setting + " " + caCertificatePath
                    + " does not exist or is not readable by user " + System.getProperty("user.name"));
        }
        return new PEMFileTrustManager(path.toFile());
    }
}
