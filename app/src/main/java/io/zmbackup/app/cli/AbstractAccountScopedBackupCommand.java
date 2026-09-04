package io.zmbackup.app.cli;

import picocli.CommandLine.Option;

abstract class AbstractAccountScopedBackupCommand extends AbstractBackupCommand {

    @Option(names = "--domain", description = "Restrict discovery to this Zimbra domain (e.g. example.com).")
    private String domain;

    @Override
    final String domain() {
        return domain;
    }
}
