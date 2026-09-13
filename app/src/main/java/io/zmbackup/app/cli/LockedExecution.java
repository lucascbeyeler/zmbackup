package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.app.config.AppConfig;
import io.zmbackup.app.config.YamlConfigLoader;
import io.zmbackup.core.port.LockContentionException;
import io.zmbackup.core.port.RunLock;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;

final class LockedExecution {

    static final int LOCK_CONTENTION_EXIT_CODE = 4;

    private LockedExecution() {}

    static Integer run(Path configFile, PrintWriter err, ContextBody body) throws Exception {
        return run(configFile, err, AppContext.ALL_LOCK_RESOURCES, body);
    }

    static Integer run(Path configFile, PrintWriter err, List<String> lockResources, ContextBody body)
            throws Exception {
        AppConfig config = YamlConfigLoader.load(configFile);
        try (RunLock lock = AppContext.acquireRunLock(config, lockResources)) {
            return body.call(new AppContext(config));
        } catch (LockContentionException e) {
            err.println(e.getMessage());
            return LOCK_CONTENTION_EXIT_CODE;
        }
    }

    interface ContextBody {
        Integer call(AppContext context) throws Exception;
    }
}
