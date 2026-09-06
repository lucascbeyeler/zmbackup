package io.zmbackup.app.config;

import java.util.List;
import java.util.Objects;

public record ServerConfigConfig(List<String> paths) {

    public static final List<String> DEFAULT_PATHS = List.of("/opt/zimbra/conf", "/opt/zimbra/ssl");

    public ServerConfigConfig {
        Objects.requireNonNull(paths, "paths must not be null");
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("serverConfig.paths must not be empty");
        }
        for (String path : paths) {
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("serverConfig.paths entries must be absolute: " + path);
            }
        }
        paths = List.copyOf(paths);
    }
}
