package io.zmbackup.app.cli;

import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Option;

abstract class AbstractAccountScopedBackupCommand extends AbstractBackupCommand {

    @Option(
            names = "--domain",
            description = "Restrict discovery to this Zimbra domain (repeatable, e.g. example.com); "
                    + "default: every domain.")
    private List<String> domains = new ArrayList<>();

    @Override
    final List<String> domains() {
        return domains;
    }
}
