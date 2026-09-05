package io.zmbackup.core.port;

import java.io.IOException;

public interface RunLock extends AutoCloseable {

    @Override
    void close() throws IOException;
}
