package io.zmbackup.app.cli;

import io.zmbackup.core.domain.BackupType;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Backs up Zimbra domains from LDAP, mirroring {@code zmbackup -f -dom} in the bash tool. */
@Command(name = "domain", description = "Back up Zimbra domains from LDAP.")
public final class BackupDomainCommand extends AbstractBackupCommand {

    @Option(names = "--domain", description = "Back up only this domain (repeatable); default: every domain.")
    private List<String> domains = new ArrayList<>();

    @Override
    BackupType type() {
        return BackupType.DOMAIN;
    }

    @Override
    List<String> identifiers() {
        return domains;
    }

    @Override
    String domain() {
        return null;
    }
}
