package io.zmbackup.core.port;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public interface ServerConfigArchiver {

    void export(OutputStream destination) throws IOException;

    List<String> restore(InputStream source) throws IOException;
}
