package io.zmbackup.app.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the actual shaded jar as a subprocess, the way the thin bash launcher does
 * ({@code java -jar zmbackup.jar}). Tests that invoke {@link Main} in-process exercise its logic
 * but can't catch packaging problems (missing Main-Class, unbundled dependencies), since they run
 * against compiled classes on the test classpath rather than the built artifact.
 */
class ShadowJarSmokeTest {

    private static final int TIMEOUT_SECONDS = 30;

    @TempDir
    Path tempDir;

    @Test
    void versionRunsAgainstPackagedJar() throws IOException, InterruptedException {
        Process process = runJar("--version");

        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        String output = outputOf(process);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("zmbackup version:"), output);
    }

    @Test
    void listRunsAgainstPackagedJar() throws IOException, InterruptedException {
        Path configFile = writeConfig();

        Process process = runJar("--config", configFile.toString(), "list");

        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        String output = outputOf(process);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Session ID"), output);
    }

    private static Process runJar(String... args) throws IOException {
        String jarPath = System.getProperty("zmbackup.shadowJar");
        String javaBin =
                Path.of(System.getProperty("java.home"), "bin", "java").toString();

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-jar");
        command.add(jarPath);
        command.addAll(List.of(args));

        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private static String outputOf(Process process) throws IOException {
        return new String(process.getInputStream().readAllBytes());
    }

    private Path writeConfig() throws IOException {
        Path configFile = tempDir.resolve("zmbackup.yaml");
        Files.writeString(
                configFile,
                """
                zimbraLdap:
                  url: ldap://127.0.0.1:389
                  bindDn: uid=zimbra,cn=admins,cn=zimbra
                  bindPassword: secret
                zimbraMailbox:
                  backupUser: %s
                  zmmailboxPath: /opt/zimbra/bin/zmmailbox
                  restBaseUrl: https://127.0.0.1:7071
                  adminUser: zimbra
                  adminPassword: secret
                backup:
                  workDir: %s
                  logFile: %s
                  blockedListFile: %s
                  emailNotify:
                    recipient: admin@example.com
                    sender: root@example.com
                """
                        .formatted(
                                System.getProperty("user.name"),
                                tempDir,
                                tempDir.resolve("zmbackup.log"),
                                tempDir.resolve("blockedlist.conf")));
        return configFile;
    }
}
