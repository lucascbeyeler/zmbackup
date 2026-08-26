package io.zmbackup.core.domain;

/**
 * The kind of backup a session performs, mirroring the bash tool's session-name prefixes
 * (e.g. {@code full-20260101120000}) so existing session IDs stay parseable.
 */
public enum BackupType {
    FULL("full", true, true),
    INCREMENTAL("inc", true, true),
    MAILBOX("mbox", false, true),
    LDAP("ldap", true, false),
    ALIAS("alias", true, false),
    DISTRIBUTION_LIST("distlist", true, false),
    SIGNATURE("signature", true, false),
    DOMAIN("domain", true, false);

    private final String sessionPrefix;
    private final boolean includesLdap;
    private final boolean includesMailbox;

    BackupType(String sessionPrefix, boolean includesLdap, boolean includesMailbox) {
        this.sessionPrefix = sessionPrefix;
        this.includesLdap = includesLdap;
        this.includesMailbox = includesMailbox;
    }

    /** The prefix used to build session IDs for this backup type, e.g. {@code "full"}. */
    public String sessionPrefix() {
        return sessionPrefix;
    }

    /** Whether this backup type exports LDAP objects (accounts, aliases, lists, signatures, domains). */
    public boolean includesLdap() {
        return includesLdap;
    }

    /** Whether this backup type exports mailbox content. */
    public boolean includesMailbox() {
        return includesMailbox;
    }
}
