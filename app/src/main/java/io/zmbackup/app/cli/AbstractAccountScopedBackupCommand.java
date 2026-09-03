package io.zmbackup.app.cli;

import picocli.CommandLine.Option;

/**
 * Shared base for the {@code backup} subcommands that filter discovery by {@code --domain} and
 * take a repeatable {@code --account}-style identifier option: {@code full}, {@code incremental},
 * {@code mailbox}, {@code ldap}, {@code alias}, {@code distlist}, {@code signature}.
 */
abstract class AbstractAccountScopedBackupCommand extends AbstractBackupCommand {

    @Option(names = "--domain", description = "Restrict discovery to this Zimbra domain (e.g. example.com).")
    private String domain;

    @Override
    final String domain() {
        return domain;
    }
}
