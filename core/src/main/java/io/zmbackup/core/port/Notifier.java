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

    /** Notifies that the backup session {@code sessionId} of {@code type} finished with {@code status}. */
    void notifyFinish(String sessionId, BackupType type, SessionStatus status) throws IOException;
}
