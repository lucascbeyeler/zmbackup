package io.zmbackup.core.port;

import io.zmbackup.core.domain.BackupType;
import io.zmbackup.core.domain.SessionStatus;
import java.io.IOException;

public interface Notifier {

    void notifyBegin(String sessionId, BackupType type) throws IOException;

    void notifyFinish(String sessionId, BackupType type, SessionStatus status, String size, int accountCount)
            throws IOException;
}
