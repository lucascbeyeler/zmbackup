package io.zmbackup.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BackupConfigTest {

    private static final EmailNotifyConfig EMAIL_NOTIFY =
            new EmailNotifyConfig(EmailNotifyLevel.ALL, "admin@example.com", "root@example.com");

    @Test
    void rejectsMaxParallelProcessesBelowOne() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new BackupConfig(
                        Path.of("/opt/zimbra/backup"),
                        Path.of("/opt/zimbra/log/zmbackup.log"),
                        Path.of("/etc/zmbackup/blockedlist.conf"),
                        0,
                        30,
                        true,
                        EMAIL_NOTIFY));

        assertEquals("maxParallelProcesses must be at least 1", exception.getMessage());
    }

    @Test
    void rejectsNegativeRotateDays() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new BackupConfig(
                        Path.of("/opt/zimbra/backup"),
                        Path.of("/opt/zimbra/log/zmbackup.log"),
                        Path.of("/etc/zmbackup/blockedlist.conf"),
                        3,
                        -1,
                        true,
                        EMAIL_NOTIFY));

        assertEquals("rotateDays must not be negative", exception.getMessage());
    }

    @Test
    void rejectsNullWorkDir() {
        assertThrows(
                NullPointerException.class,
                () -> new BackupConfig(
                        null,
                        Path.of("/opt/zimbra/log/zmbackup.log"),
                        Path.of("/etc/zmbackup/blockedlist.conf"),
                        3,
                        30,
                        true,
                        EMAIL_NOTIFY));
    }

    @Test
    void rejectsNullLogFile() {
        assertThrows(
                NullPointerException.class,
                () -> new BackupConfig(
                        Path.of("/opt/zimbra/backup"),
                        null,
                        Path.of("/etc/zmbackup/blockedlist.conf"),
                        3,
                        30,
                        true,
                        EMAIL_NOTIFY));
    }

    @Test
    void rejectsNullBlockedListFile() {
        assertThrows(
                NullPointerException.class,
                () -> new BackupConfig(
                        Path.of("/opt/zimbra/backup"), Path.of("/opt/zimbra/log/zmbackup.log"), null, 3, 30, true,
                        EMAIL_NOTIFY));
    }

    @Test
    void rejectsNullEmailNotify() {
        assertThrows(
                NullPointerException.class,
                () -> new BackupConfig(
                        Path.of("/opt/zimbra/backup"),
                        Path.of("/opt/zimbra/log/zmbackup.log"),
                        Path.of("/etc/zmbackup/blockedlist.conf"),
                        3,
                        30,
                        true,
                        null));
    }

    @Test
    void acceptsZeroRotateDaysAndOneMaxParallelProcess() {
        BackupConfig config = new BackupConfig(
                Path.of("/opt/zimbra/backup"),
                Path.of("/opt/zimbra/log/zmbackup.log"),
                Path.of("/etc/zmbackup/blockedlist.conf"),
                1,
                0,
                true,
                EMAIL_NOTIFY);

        assertEquals(1, config.maxParallelProcesses());
        assertEquals(0, config.rotateDays());
    }
}
