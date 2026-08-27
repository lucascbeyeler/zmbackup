package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.domain.BackupType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** Backs up Zimbra aliases from LDAP, mirroring {@code zmbackup -f -alp} in the bash tool. */
@Command(name = "alias", description = "Back up Zimbra aliases from LDAP.")
public final class BackupAliasCommand implements Callable<Integer> {

    @ParentCommand
    private BackupCommand parent;

    @Option(names = "--account", description = "Back up only this alias (repeatable); default: every alias.")
    private List<String> accounts = new ArrayList<>();

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        AppContext context = AppContext.fromConfigFile(parent.parent().configFile());
        return BackupRunner.run(context, spec.commandLine().getOut(), BackupType.ALIAS, accounts, null);
    }
}
