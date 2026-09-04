package io.zmbackup.app.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import picocli.CommandLine.IVersionProvider;

public final class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() throws IOException {
        try (InputStream in = VersionProvider.class.getResourceAsStream("/VERSION")) {
            String version = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return new String[] {"zmbackup version: " + version};
        }
    }
}
