package io.zmbackup.core.port;

import io.zmbackup.core.domain.LdapObjectType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Exports and restores a single LDAP object's raw {@code .ldiff} entry, mirroring
 * {@code ldap_backup}/{@code ldap_restore}/{@code domain_backup}/{@code domain_restore} in the
 * bash tool's {@code ParallelAction.sh}.
 */
public interface ZimbraLdapExporter {

    /**
     * Writes the {@code .ldiff} entry for the object identified by {@code identifier} and
     * {@code type} to {@code destination}.
     */
    void export(String identifier, LdapObjectType type, OutputStream destination) throws IOException;

    /**
     * Writes the {@code .ldiff} entry for the domain identified by {@code domain} (e.g. {@code
     * "example.com"}) to {@code destination}.
     */
    void exportDomain(String domain, OutputStream destination) throws IOException;

    /**
     * Restores an object of the given {@code type} from a previously exported {@code .ldiff}
     * entry, replacing any existing entry with the same distinguished name.
     */
    void restore(LdapObjectType type, InputStream source) throws IOException;

    /**
     * Restores a domain entry from a previously exported {@code .ldiff} entry, adding it without
     * first deleting any existing entry. Unlike {@link #restore}, an entry that already exists is
     * treated as success rather than a failure, mirroring {@code domain_restore}'s handling of
     * {@code ldapadd}'s "Already exists" error in the bash tool's {@code ParallelAction.sh}: a
     * clean install's domain parent entries are expected to already exist ahead of account
     * restores.
     */
    void restoreDomain(InputStream source) throws IOException;
}
