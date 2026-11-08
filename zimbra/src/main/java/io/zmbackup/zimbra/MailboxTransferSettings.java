package io.zmbackup.zimbra;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Timeouts, batching and retry settings for {@link ZimbraRestMailboxExporter}.
 *
 * @param exportHeaderTimeout how long an export request may wait for Zimbra's response headers
 * @param exportInactivityTimeout how long an export body may go without a byte before the stream is
 *     closed and the request cancelled. A healthy export of any length never hits it
 * @param restoreTimeout how long a restore POST may wait for Zimbra's reply. Zimbra replies only
 *     after the whole import, so this bounds one restore request (one batch when batching)
 * @param batchedExport fetch items in {@code list=} batches after a metadata-only pass, instead of one
 *     stream for the whole mailbox
 * @param batchedRestore post the folder structure first, then the items in batches
 * @param exportJournalDir where batched exports keep their resume journal, one folder per account.
 *     {@code null} uses a temporary folder per export, so a failed export doesn't resume
 * @param retries how many times a failed request (one batch, or the metadata pass) is retried
 * @param retryBackoff the wait before the first retry; it doubles on each further retry
 * @param journalMaxAge a journal older than this is discarded instead of resumed, so a backup never
 *     mixes items fetched too far apart
 */
public record MailboxTransferSettings(
        Duration exportHeaderTimeout,
        Duration exportInactivityTimeout,
        Duration restoreTimeout,
        boolean batchedExport,
        boolean batchedRestore,
        Path exportJournalDir,
        int retries,
        Duration retryBackoff,
        Duration journalMaxAge) {

    public static final Duration DEFAULT_EXPORT_HEADER_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration DEFAULT_EXPORT_INACTIVITY_TIMEOUT = Duration.ofMinutes(10);
    public static final Duration DEFAULT_RESTORE_TIMEOUT = Duration.ofHours(24);
    public static final int DEFAULT_RETRIES = 3;
    public static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(2);
    public static final Duration DEFAULT_JOURNAL_MAX_AGE = Duration.ofHours(24);

    public MailboxTransferSettings {
        requirePositive(exportHeaderTimeout, "exportHeaderTimeout");
        requirePositive(exportInactivityTimeout, "exportInactivityTimeout");
        requirePositive(restoreTimeout, "restoreTimeout");
        requirePositive(journalMaxAge, "journalMaxAge");
        Objects.requireNonNull(retryBackoff, "retryBackoff must not be null");
        if (retryBackoff.isNegative()) {
            throw new IllegalArgumentException("retryBackoff must not be negative");
        }
        if (retries < 0) {
            throw new IllegalArgumentException("retries must not be negative");
        }
    }

    public static MailboxTransferSettings defaults() {
        return new MailboxTransferSettings(
                DEFAULT_EXPORT_HEADER_TIMEOUT,
                DEFAULT_EXPORT_INACTIVITY_TIMEOUT,
                DEFAULT_RESTORE_TIMEOUT,
                true,
                true,
                null,
                DEFAULT_RETRIES,
                DEFAULT_RETRY_BACKOFF,
                DEFAULT_JOURNAL_MAX_AGE);
    }

    public MailboxTransferSettings withExportJournalDir(Path dir) {
        return new MailboxTransferSettings(
                exportHeaderTimeout, exportInactivityTimeout, restoreTimeout, batchedExport, batchedRestore,
                dir, retries, retryBackoff, journalMaxAge);
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
