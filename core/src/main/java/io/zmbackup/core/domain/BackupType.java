package io.zmbackup.core.domain;

import java.util.Arrays;
import java.util.List;

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

    public String sessionPrefix() {
        return sessionPrefix;
    }

    public boolean includesLdap() {
        return includesLdap;
    }

    public boolean includesMailbox() {
        return includesMailbox;
    }

    public static List<String> mailboxSessionPrefixes() {
        return Arrays.stream(values())
                .filter(BackupType::includesMailbox)
                .map(BackupType::sessionPrefix)
                .toList();
    }

    public static BackupType fromSessionPrefix(String sessionPrefix) {
        for (BackupType type : values()) {
            if (type.sessionPrefix.equals(sessionPrefix)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown backup type session prefix: " + sessionPrefix);
    }
}
