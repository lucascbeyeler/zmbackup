package io.zmbackup.core.port;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface ServerConfigArchiver {

    void export(OutputStream destination) throws IOException;

    void restore(InputStream source) throws IOException;
}
