package io.zmbackup.core.port;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;

/**
 * Exports and restores a single account's mailbox content as a {@code .tgz} archive, mirroring
 * {@code mailbox_backup}/{@code mailbox_restore} in the bash tool's {@code ParallelAction.sh}.
 */
public interface ZimbraMailboxExporter {

    /** Exports the full mailbox content of {@code account} to {@code destination}. */
    default boolean export(String account, OutputStream destination) throws IOException {
        return export(account, destination, null);
    }

    /**
     * Exports {@code account}'s mailbox content to {@code destination}, limited to items after
     * {@code since} when non-null. Returns {@code false} without writing anything when there is
     * no content to export (e.g. nothing changed since an incremental cutoff).
     */
    boolean export(String account, OutputStream destination, Instant since) throws IOException;

    /** Restores {@code account}'s mailbox content from a previously exported {@code .tgz} archive. */
    void restore(String account, InputStream source) throws IOException;
}
