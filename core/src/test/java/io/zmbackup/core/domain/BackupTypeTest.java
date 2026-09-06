package io.zmbackup.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BackupTypeTest {

    @Test
    void fullIncludesLdapAndMailbox() {
        assertTrue(BackupType.FULL.includesLdap());
        assertTrue(BackupType.FULL.includesMailbox());
        assertEquals("full", BackupType.FULL.sessionPrefix());
    }

    @Test
    void incrementalIncludesLdapAndMailbox() {
        assertTrue(BackupType.INCREMENTAL.includesLdap());
        assertTrue(BackupType.INCREMENTAL.includesMailbox());
        assertEquals("inc", BackupType.INCREMENTAL.sessionPrefix());
    }

    @Test
    void mailboxIncludesMailboxOnly() {
        assertFalse(BackupType.MAILBOX.includesLdap());
        assertTrue(BackupType.MAILBOX.includesMailbox());
        assertEquals("mbox", BackupType.MAILBOX.sessionPrefix());
    }

    @Test
    void ldapOnlyTypesIncludeLdapButNotMailbox() {
        for (BackupType type : new BackupType[] {
                BackupType.LDAP, BackupType.ALIAS, BackupType.DISTRIBUTION_LIST,
                BackupType.SIGNATURE, BackupType.DOMAIN}) {
            assertTrue(type.includesLdap(), type + " should include LDAP");
            assertFalse(type.includesMailbox(), type + " should not include mailbox");
        }
    }

    @Test
    void sessionPrefixesMatchBashToolConventions() {
        assertEquals("ldap", BackupType.LDAP.sessionPrefix());
        assertEquals("alias", BackupType.ALIAS.sessionPrefix());
        assertEquals("distlist", BackupType.DISTRIBUTION_LIST.sessionPrefix());
        assertEquals("signature", BackupType.SIGNATURE.sessionPrefix());
        assertEquals("domain", BackupType.DOMAIN.sessionPrefix());
    }

    @Test
    void mailboxSessionPrefixesListsOnlyTypesThatIncludeMailbox() {
        assertEquals(List.of("full", "inc", "mbox"), BackupType.mailboxSessionPrefixes());
    }

    @Test
    void ldapAndMailboxDoNotConflictWithEachOther() {
        assertFalse(BackupType.LDAP.conflictingSessionPrefixes().contains("mbox"));
        assertFalse(BackupType.MAILBOX.conflictingSessionPrefixes().contains("ldap"));
    }

    @Test
    void fullAndIncrementalConflictWithBothLdapAndMailbox() {
        assertEquals(List.of("full", "inc", "mbox", "ldap", "alias", "distlist", "signature", "domain"),
                BackupType.FULL.conflictingSessionPrefixes());
        assertEquals(List.of("full", "inc", "mbox", "ldap", "alias", "distlist", "signature", "domain"),
                BackupType.INCREMENTAL.conflictingSessionPrefixes());
    }

    @Test
    void mailboxConflictsOnlyWithMailboxCoveringTypes() {
        assertEquals(List.of("full", "inc", "mbox"), BackupType.MAILBOX.conflictingSessionPrefixes());
    }

    @Test
    void ldapConflictsWithEveryLdapCoveringTypeButNotMailbox() {
        assertEquals(
                List.of("full", "inc", "ldap", "alias", "distlist", "signature", "domain"),
                BackupType.LDAP.conflictingSessionPrefixes());
    }

    @Test
    void serverConfigIncludesNeitherLdapNorMailbox() {
        assertFalse(BackupType.SERVER_CONFIG.includesLdap());
        assertFalse(BackupType.SERVER_CONFIG.includesMailbox());
        assertEquals("serverconfig", BackupType.SERVER_CONFIG.sessionPrefix());
    }

    @Test
    void serverConfigConflictsOnlyWithItselfDespiteHavingNoLdapOrMailboxFlag() {
        assertEquals(List.of("serverconfig"), BackupType.SERVER_CONFIG.conflictingSessionPrefixes());
    }
}
