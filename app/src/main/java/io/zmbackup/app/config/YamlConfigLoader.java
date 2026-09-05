package io.zmbackup.app.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlConfigLoader {

    public static final Path DEFAULT_CONFIG_PATH = Path.of("/etc/zmbackup/zmbackup.yaml");

    private YamlConfigLoader() {}

    private static Yaml newYaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    public static AppConfig load(Path configFile) throws IOException {
        try (Reader reader = Files.newBufferedReader(configFile)) {
            return load(reader);
        } catch (NoSuchFileException e) {
            throw new ConfigException("Config file not found: " + configFile, e);
        }
    }

    public static AppConfig load(InputStream in) {
        return load(newYaml().<Map<String, Object>>load(in));
    }

    public static AppConfig load(Reader reader) {
        return load(newYaml().<Map<String, Object>>load(reader));
    }

    private static AppConfig load(Map<String, Object> root) {
        if (root == null) {
            throw new ConfigException("Config file is empty");
        }
        return new AppConfig(
                parseZimbraLdap(root),
                parseZimbraMailbox(root),
                parseBackup(root),
                parseStorage(root),
                parseMetadata(root),
                optionalBoolean(root, "allowInsecure", false));
    }

    private static StorageConfig parseStorage(Map<String, Object> root) {
        StorageBackend backend = optionalEnum(root, "storage.backend", StorageBackend.class, StorageBackend.LOCAL);
        return new StorageConfig(backend, backend == StorageBackend.S3 ? parseS3(root) : null);
    }

    private static S3Config parseS3(Map<String, Object> root) {
        return new S3Config(
                requireString(root, "storage.s3.bucket"),
                requireString(root, "storage.s3.region"),
                optionalStringDefault(root, "storage.s3.prefix", S3Config.DEFAULT_PREFIX),
                optionalUri(root, "storage.s3.endpointOverride"));
    }

    private static MetadataConfig parseMetadata(Map<String, Object> root) {
        MetadataBackend backend =
                optionalEnum(root, "metadata.backend", MetadataBackend.class, MetadataBackend.SQLITE);
        return new MetadataConfig(backend, backend == MetadataBackend.DYNAMODB ? parseDynamoDb(root) : null);
    }

    private static DynamoDbConfig parseDynamoDb(Map<String, Object> root) {
        return new DynamoDbConfig(
                requireString(root, "metadata.dynamodb.region"),
                optionalStringDefault(
                        root, "metadata.dynamodb.sessionTable", DynamoDbConfig.DEFAULT_SESSION_TABLE),
                optionalStringDefault(
                        root, "metadata.dynamodb.accountTable", DynamoDbConfig.DEFAULT_ACCOUNT_TABLE),
                optionalStringDefault(root, "metadata.dynamodb.lockTable", DynamoDbConfig.DEFAULT_LOCK_TABLE),
                optionalUri(root, "metadata.dynamodb.endpointOverride"));
    }

    private static ZimbraLdapConfig parseZimbraLdap(Map<String, Object> root) {
        return new ZimbraLdapConfig(
                requireString(root, "zimbraLdap.url"),
                requireString(root, "zimbraLdap.bindDn"),
                requireString(root, "zimbraLdap.bindPassword"),
                optionalBoolean(root, "zimbraLdap.sslEnabled", true),
                optionalString(root, "zimbraLdap.caCertificatePath"),
                optionalBoolean(root, "zimbraLdap.trustAllCertificates", false),
                optionalInt(
                        root,
                        "zimbraLdap.responseTimeoutSeconds",
                        ZimbraLdapConfig.DEFAULT_RESPONSE_TIMEOUT_SECONDS));
    }

    private static ZimbraMailboxConfig parseZimbraMailbox(Map<String, Object> root) {
        return new ZimbraMailboxConfig(
                requireString(root, "zimbraMailbox.backupUser"),
                optionalBoolean(root, "zimbraMailbox.backupInactiveAccounts", true),
                requireString(root, "zimbraMailbox.restBaseUrl"),
                requireString(root, "zimbraMailbox.adminUser"),
                requireString(root, "zimbraMailbox.adminPassword"),
                optionalString(root, "zimbraMailbox.caCertificatePath"),
                optionalBoolean(root, "zimbraMailbox.trustAllCertificates", false));
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
        EmailNotifyLevel level = optionalEmailNotifyLevel(root, "backup.emailNotify.level", EmailNotifyLevel.ALL);
        boolean notifyDisabled = level == EmailNotifyLevel.NONE;
        return new EmailNotifyConfig(
                level,
                notifyDisabled
                        ? optionalString(root, "backup.emailNotify.recipient")
                        : requireString(root, "backup.emailNotify.recipient"),
                notifyDisabled
                        ? optionalString(root, "backup.emailNotify.sender")
                        : requireString(root, "backup.emailNotify.sender"));
    }

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

    private static String optionalStringDefault(Map<String, Object> root, String dottedPath, String defaultValue) {
        String value = optionalString(root, dottedPath);
        return value == null ? defaultValue : value;
    }

    private static URI optionalUri(Map<String, Object> root, String dottedPath) {
        String value = optionalString(root, dottedPath);
        if (value == null) {
            return null;
        }
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Invalid URI at '" + dottedPath + "': " + value, e);
        }
    }

    private static boolean optionalBoolean(Map<String, Object> root, String dottedPath, boolean defaultValue) {
        Object value = get(root, dottedPath);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            Optional<Boolean> parsed = parseBoolean(text);
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        throw new ConfigException("Expected a boolean at '" + dottedPath + "', got: " + value);
    }

    private static Optional<Boolean> parseBoolean(String text) {
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on" -> Optional.of(Boolean.TRUE);
            case "false", "no", "off" -> Optional.of(Boolean.FALSE);
            default -> Optional.empty();
        };
    }

    private static int optionalInt(Map<String, Object> root, String dottedPath, int defaultValue) {
        Object value = get(root, dottedPath);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            long asLong = number.longValue();
            if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
                throw new ConfigException("Value at '" + dottedPath + "' is out of range: " + value);
            }
            return (int) asLong;
        }
        throw new ConfigException("Expected an integer at '" + dottedPath + "', got: " + value);
    }

    private static EmailNotifyLevel optionalEmailNotifyLevel(
            Map<String, Object> root, String dottedPath, EmailNotifyLevel defaultValue) {
        return optionalEnum(root, dottedPath, EmailNotifyLevel.class, defaultValue);
    }

    private static <E extends Enum<E>> E optionalEnum(
            Map<String, Object> root, String dottedPath, Class<E> enumType, E defaultValue) {
        Object value = get(root, dottedPath);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumType, value.toString().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Invalid value for '" + dottedPath + "': " + value, e);
        }
    }
}
