package io.zmbackup.core.port;

import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import java.io.IOException;

/**
 * Sends e-mail notifications about a backup session's lifecycle, mirroring {@code
 * notify_begin}/{@code notify_finish} in the bash tool's {@code NotifyAction.sh}.
 */
public interface Notifier {

    /** Notifies that the backup session {@code sessionId} of {@code type} has started. */
    void notifyBegin(String sessionId, BackupType type) throws IOException;

    /**
     * Notifies that the backup session {@code sessionId} of {@code type} finished with
     * {@code status}, mirroring {@code notify_finish}'s {@code Size}/{@code Accounts} summary
     * lines.
     *
     * @param size         the session's total stored size (e.g. {@code "10M"}), or {@code "0"}
     *                     when {@code status} is not {@link SessionStatus#FINISHED}, mirroring
     *                     {@code notify_finish}'s {@code SIZE=0} on failure
     * @param accountCount how many objects were successfully backed up, or {@code 0} when
     *                     {@code status} is not {@link SessionStatus#FINISHED}, mirroring
     *                     {@code notify_finish}'s {@code QTDE=0} on failure
     */
    void notifyFinish(String sessionId, BackupType type, SessionStatus status, String size, int accountCount)
            throws IOException;
}
