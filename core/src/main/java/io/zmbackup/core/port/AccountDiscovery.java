package io.zmbackup.core.port;

import io.zmbackup.core.domain.LdapObjectType;
import java.io.IOException;
import java.util.List;

/**
 * Enumerates the LDAP objects eligible for backup, mirroring {@code build_listBKP} in the bash
 * tool's {@code MiscAction.sh}.
 */
public interface AccountDiscovery {

    /**
     * The identifying attribute value ({@link LdapObjectType#attributeName()}) of every object
     * of {@code type} found across the whole directory.
     */
    List<String> discover(LdapObjectType type) throws IOException;

    /**
     * Same as {@link #discover(LdapObjectType)}, scoped to a single {@code domain} (e.g.
     * {@code "example.com"}).
     */
    List<String> discoverForDomain(LdapObjectType type, String domain) throws IOException;
}
