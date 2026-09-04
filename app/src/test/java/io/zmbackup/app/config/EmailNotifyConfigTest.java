package io.zmbackup.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EmailNotifyConfigTest {

    @Test
    void rejectsNullLevel() {
        assertThrows(
                NullPointerException.class,
                () -> new EmailNotifyConfig(null, "admin@example.com", "root@example.com"));
    }

    @Test
    void rejectsNullRecipient() {
        assertThrows(
                NullPointerException.class, () -> new EmailNotifyConfig(EmailNotifyLevel.ALL, null, "root@example.com"));
    }

    @Test
    void rejectsNullSender() {
        assertThrows(
                NullPointerException.class,
                () -> new EmailNotifyConfig(EmailNotifyLevel.ALL, "admin@example.com", null));
    }

    @Test
    void allowsNullRecipientAndSenderWhenLevelIsNone() {
        EmailNotifyConfig config = new EmailNotifyConfig(EmailNotifyLevel.NONE, null, null);

        assertEquals(EmailNotifyLevel.NONE, config.level());
        assertEquals(null, config.recipient());
        assertEquals(null, config.sender());
    }

    @Test
    void storesConfiguredFields() {
        EmailNotifyConfig config = new EmailNotifyConfig(EmailNotifyLevel.ERROR, "admin@example.com", "root@example.com");

        assertEquals(EmailNotifyLevel.ERROR, config.level());
        assertEquals("admin@example.com", config.recipient());
        assertEquals("root@example.com", config.sender());
    }
}
