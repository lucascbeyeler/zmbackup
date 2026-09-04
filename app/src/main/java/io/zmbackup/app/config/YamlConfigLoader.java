package io.zmbackup.app.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parses {@code zmbackup.yaml} into an {@link AppConfig}, mirroring the fields of the bash tool's
 * {@code zmbackup.conf}.
 */
public final class YamlConfigLoader {

    /** Where the bash tool's installer places {@code zmbackup.conf}; the Java tool's equivalent. */
    public static final Path DEFAULT_CONFIG_PATH = Path.of("/etc/zmbackup/zmbackup.yaml");

    private YamlConfigLoader() {}

    /**
     * Restricted to plain maps/scalars: config content is operator-controlled but there is no
     * reason to allow it to instantiate arbitrary Java types via {@code !!}-tagged values, which
     * the default {@link Yaml} constructor otherwise permits.
     */
    private static Yaml newYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    /** Reads and parses the config file at {@code configFile}. */
    public static AppConfig load(Path configFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(configFile)) {
            return load(reader);
        } catch (NoSuchFileException e) {
            throw new ConfigException("Config file not found: " + configFile, e);
        }
    }

    /** Parses config content already available as a stream, e.g. a bundled resource. */
    public static AppConfig load(InputStream in) {
        return load(newYaml().<Map<String, Object>>load(in));
    }

    /** Parses config content already available as a reader. */
    public static AppConfig load(Reader reader) {
        return load(newYaml().<Map<String, Object>>load(reader));
    }

    private static AppConfig load(Map<String, Object> root) {
        if (root == null) {
            throw new ConfigException("Config file is empty");
        }
        return new AppConfig(parseZimbraLdap(root), parseZimbraMailbox(root), parseBackup(root));
    }

    private static ZimbraLdapConfig parseZimbraLdap(Map<String, Object> root) {
        return new ZimbraLdapConfig(
                requireString(root, "zimbraLdap.url"),
                requireString(root, "zimbraLdap.bindDn"),
                requireString(root, "zimbraLdap.bindPassword"),
                optionalBoolean(root, "zimbraLdap.sslEnabled", true),
                optionalString(root, "zimbraLdap.caCertificatePath"),
                optionalBoolean(root, "zimbraLdap.trustAllCertificates", false));
    }

    private static ZimbraMailboxConfig parseZimbraMailbox(Map<String, Object> root) {
        return new ZimbraMailboxConfig(
                requireString(root, "zimbraMailbox.backupUser"),
                requirePath(root, "zimbraMailbox.zmmailboxPath"),
                optionalBoolean(root, "zimbraMailbox.backupInactiveAccounts", true),
                requireString(root, "zimbraMailbox.restBaseUrl"),
                requireString(root, "zimbraMailbox.adminUser"),
                requireString(root, "zimbraMailbox.adminPassword"));
    }

    private static BackupConfig parseBackup(Map<String, Object> root) {
        return new BackupConfig(
                requirePath(root, "backup.workDir"),
                requirePath(root, "backup.logFile"),
                requirePath(root, "backup.blockedListFile"),
                optionalInt(root, "backup.maxParallelProcesses", 3),
                optionalInt(root, "backup.rotateDays", 30),
                optionalBoolean(root, "backup.lockBackup", true),
                parseEmailNotify(root));
    }

    private static EmailNotifyConfig parseEmailNotify(Map<String, Object> root) {
        return new EmailNotifyConfig(
                optionalEmailNotifyLevel(root, "backup.emailNotify.level", EmailNotifyLevel.ALL),
                requireString(root, "backup.emailNotify.recipient"),
                requireString(root, "backup.emailNotify.sender"));
    }

    /**
     * Navigates {@code root} following {@code dottedPath} (e.g. {@code "backup.workDir"}),
     * returning {@code null} if any segment along the way is absent.
     */
    private static Object get(Map<String, Object> root, String dottedPath) {
        Object current = root;
        StringBuilder soFar = new StringBuilder();
        for (String segment : dottedPath.split("\\.")) {
            if (!soFar.isEmpty()) {
                soFar.append('.');
            }
            soFar.append(segment);
            if (current == null) {
                return null;
            }
            if (!(current instanceof Map)) {
                throw new ConfigException("Expected a mapping at '" + soFar + "'");
            }
            current = ((Map<?, ?>) current).get(segment);
        }
        return current;
    }

    private static String requireString(Map<String, Object> root, String dottedPath) {
        Object value = get(root, dottedPath);
        if (value == null) {
            throw new ConfigException("Missing required config value: " + dottedPath);
        }
        return value.toString();
    }

    private static Path requirePath(Map<String, Object> root, String dottedPath) {
        return Path.of(requireString(root, dottedPath));
    }

    private static String optionalString(Map<String, Object> root, String dottedPath) {
        Object value = get(root, dottedPath);
        return value == null ? null : value.toString();
    }

    private static boolean optionalBoolean(Map<String, Object> root, String dottedPath, boolean defaultValue) {
        Object value = get(root, dottedPath);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new ConfigException("Expected a boolean at '" + dottedPath + "', got: " + value);
    }

    private static int optionalInt(Map<String, Object> root, String dottedPath, int defaultValue) {
        Object value = get(root, dottedPath);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Integer intValue) {
            return intValue;
        }
        throw new ConfigException("Expected an integer at '" + dottedPath + "', got: " + value);
    }

    private static EmailNotifyLevel optionalEmailNotifyLevel(
            Map<String, Object> root, String dottedPath, EmailNotifyLevel defaultValue) {
        Object value = get(root, dottedPath);
        if (value == null) {
            return defaultValue;
        }
        try {
            return EmailNotifyLevel.valueOf(value.toString().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Invalid value for '" + dottedPath + "': " + value, e);
        }
    }
}
