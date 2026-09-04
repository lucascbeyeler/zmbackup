package io.zmbackup.core.port;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface StorageProvider {

    OutputStream openWrite(String sessionId, String account, String suffix) throws IOException;

    InputStream openRead(String sessionId, String account, String suffix) throws IOException;

    boolean exists(String sessionId, String account, String suffix);

    String sizeOfAccount(String sessionId, String account) throws IOException;

    String sizeOfSession(String sessionId) throws IOException;

    void deleteSession(String sessionId) throws IOException;

    int deleteEmptyFiles() throws IOException;
}
