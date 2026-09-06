package io.zmbackup.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class EmailNotifyConfigTest {

    @Test
    void rejectsNullLevel() {
        assertThrows(
                NullPointerException.class,
                () -> new EmailNotifyConfig(
                        null, "admin@example.com", "root@example.com", "localhost", 25));
    }

    @Test
    void rejectsNullRecipient() {
        assertThrows(
                NullPointerException.class,
                () -> new EmailNotifyConfig(
                        EmailNotifyLevel.ALL, null, "root@example.com", "localhost", 25));
    }

    @Test
    void rejectsNullSender() {
        assertThrows(
                NullPointerException.class,
                () -> new EmailNotifyConfig(
                        EmailNotifyLevel.ALL, "admin@example.com", null, "localhost", 25));
    }

    @Test
    void rejectsNullSmtpHost() {
        assertThrows(
                NullPointerException.class,
                () -> new EmailNotifyConfig(
                        EmailNotifyLevel.ALL, "admin@example.com", "root@example.com", null, 25));
    }

    @Test
    void rejectsSmtpPortBelowOne() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailNotifyConfig(
                        EmailNotifyLevel.ALL, "admin@example.com", "root@example.com", "localhost", 0));
    }

    @Test
    void rejectsSmtpPortAboveSixtyFiveThirtyFive() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new EmailNotifyConfig(
                        EmailNotifyLevel.ALL, "admin@example.com", "root@example.com", "localhost", 65536));
    }

    @Test
    void allowsNullRecipientAndSenderWhenLevelIsNone() {
        EmailNotifyConfig config = new EmailNotifyConfig(EmailNotifyLevel.NONE, null, null, "localhost", 25);

        assertEquals(EmailNotifyLevel.NONE, config.level());
        assertEquals(null, config.recipient());
        assertEquals(null, config.sender());
    }

    @Test
    void storesConfiguredFields() {
        EmailNotifyConfig config = new EmailNotifyConfig(
                EmailNotifyLevel.ERROR, "admin@example.com", "root@example.com", "mail.example.com", 587);

        assertEquals(EmailNotifyLevel.ERROR, config.level());
        assertEquals("admin@example.com", config.recipient());
        assertEquals("root@example.com", config.sender());
        assertEquals("mail.example.com", config.smtpHost());
        assertEquals(587, config.smtpPort());
    }
}
