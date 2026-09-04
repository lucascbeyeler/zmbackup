package io.zmbackup.core.port;

public interface Blocklist {

    boolean isBlocked(String identifier);
}
