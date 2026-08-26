package io.zmbackup.app.config;

/** Thrown when {@code zmbackup.yaml} is missing, malformed, or missing a required field. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
