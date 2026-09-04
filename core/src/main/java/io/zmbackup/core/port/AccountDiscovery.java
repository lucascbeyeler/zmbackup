package io.zmbackup.core.port;

import io.zmbackup.core.domain.LdapObjectType;
import java.io.IOException;
import java.util.List;

public interface AccountDiscovery {

    List<String> discover(LdapObjectType type) throws IOException;

    List<String> discoverForDomain(LdapObjectType type, String domain) throws IOException;

    default List<String> listDomains() throws IOException {
        return discover(LdapObjectType.DOMAIN);
    }
}
