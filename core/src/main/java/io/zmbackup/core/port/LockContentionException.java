package io.zmbackup.core.port;

import java.io.IOException;

public class LockContentionException extends IOException {

    public LockContentionException(String message) {
        super(message);
    }
}
