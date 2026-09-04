package io.zmbackup.core.port;

import io.zmbackup.core.domain.LdapObjectType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface ZimbraLdapExporter {

    void export(String identifier, LdapObjectType type, OutputStream destination) throws IOException;

    void exportDomain(String domain, OutputStream destination) throws IOException;

    void restore(LdapObjectType type, InputStream source) throws IOException;

    void restoreDomain(InputStream source) throws IOException;
}
