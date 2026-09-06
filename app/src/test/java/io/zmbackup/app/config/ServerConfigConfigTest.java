package io.zmbackup.app.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class ServerConfigConfigTest {

    @Test
    void rejectsNullPaths() {
        assertThrows(NullPointerException.class, () -> new ServerConfigConfig(null));
    }

    @Test
    void rejectsEmptyPaths() {
        IllegalArgumentException exception =
                assertThrows(IllegalArgumentException.class, () -> new ServerConfigConfig(List.of()));

        assertEquals("serverConfig.paths must not be empty", exception.getMessage());
    }

    @Test
    void rejectsRelativePath() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class, () -> new ServerConfigConfig(List.of("opt/zimbra/conf")));

        assertEquals("serverConfig.paths entries must be absolute: opt/zimbra/conf", exception.getMessage());
    }

    @Test
    void acceptsAbsolutePaths() {
        ServerConfigConfig config = new ServerConfigConfig(List.of("/opt/zimbra/conf", "/opt/zimbra/ssl"));

        assertEquals(List.of("/opt/zimbra/conf", "/opt/zimbra/ssl"), config.paths());
    }

    @Test
    void defaultPathsCoverConfAndSsl() {
        assertEquals(List.of("/opt/zimbra/conf", "/opt/zimbra/ssl"), ServerConfigConfig.DEFAULT_PATHS);
    }
}
