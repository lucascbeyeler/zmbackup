package io.zmbackup.core.port;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Stores and retrieves the raw backup content (LDAP {@code .ldiff} exports and mailbox
 * {@code .tgz} archives) produced for a session, mirroring the bash tool's
 * {@code {WORKDIR}/{sessionId}/{account}.{suffix}} file layout without committing callers to a
 * particular storage backend.
 */
public interface StorageProvider {

    /**
     * Opens a stream to write the content identified by {@code account} and {@code suffix}
     * (e.g. {@code "ldiff"}, {@code "tgz"}) within {@code sessionId}. Any existing content at
     * the same location is replaced.
     */
    OutputStream openWrite(String sessionId, String account, String suffix) throws IOException;

    /** Opens a stream to read back content previously written with {@link #openWrite}. */
    InputStream openRead(String sessionId, String account, String suffix) throws IOException;

    /** Whether content was written for {@code account} and {@code suffix} within {@code sessionId}. */
    boolean exists(String sessionId, String account, String suffix);

    /**
     * The human-readable total size of the content stored for {@code account} within
     * {@code sessionId} (e.g. {@code "10M"}), for
     * {@link io.zmbackup.core.domain.BackupAccountRecord#size()}.
     */
    String sizeOfAccount(String sessionId, String account) throws IOException;

    /**
     * The human-readable total size of all content stored for {@code sessionId} (e.g.
     * {@code "10M"}), for {@link io.zmbackup.core.domain.BackupSession#size()}.
     */
    String sizeOfSession(String sessionId) throws IOException;

    /** Permanently removes all stored content for {@code sessionId}. */
    void deleteSession(String sessionId) throws IOException;
}
