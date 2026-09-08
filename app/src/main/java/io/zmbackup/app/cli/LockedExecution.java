package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.app.config.AppConfig;
import io.zmbackup.app.config.YamlConfigLoader;
import io.zmbackup.core.port.LockContentionException;
import io.zmbackup.core.port.RunLock;
import java.io.PrintWriter;
import java.nio.file.Path;
import picocli.CommandLine;

final class LockedExecution {

    private LockedExecution() {}

    static Integer run(Path configFile, PrintWriter err, ContextBody body) throws Exception {
        AppConfig config = YamlConfigLoader.load(configFile);
        try (RunLock lock = AppContext.acquireRunLock(config)) {
            return body.call(new AppContext(config));
        } catch (LockContentionException e) {
            err.println(e.getMessage());
            return CommandLine.ExitCode.SOFTWARE;
        }
    }

    interface ContextBody {
        Integer call(AppContext context) throws Exception;
    }
}
