package io.zmbackup.core.port;

/**
 * Accounts that must never be backed up, mirroring {@code ldap_filter}'s blocklist check against
 * {@code ZMBACKUP_BLOCKEDLIST} in the bash tool's {@code ParallelAction.sh}.
 */
public interface Blocklist {

    /** Whether {@code identifier} (an account, or domain) must be skipped. */
    boolean isBlocked(String identifier);
}
