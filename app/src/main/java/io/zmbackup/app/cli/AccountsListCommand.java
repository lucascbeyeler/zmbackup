package io.zmbackup.app.cli;

import io.zmbackup.app.AppContext;
import io.zmbackup.core.domain.LdapObjectType;
import io.zmbackup.core.port.AccountDiscovery;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * Prints every Zimbra account found in LDAP, mirroring {@code zmbackup -f -ldp} in the bash tool.
 * Diagnostic only: it does not perform a backup.
 */
@Command(name = "list", description = "List Zimbra accounts from LDAP (diagnostic; not a backup operation).")
public final class AccountsListCommand implements Callable<Integer> {

    @ParentCommand
    private AccountsCommand parent;

    @Option(names = "--domain", description = "Restrict the listing to this Zimbra domain (e.g. example.com).")
    private String domain;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        if (!CliValidation.validateDomain(domain, spec.commandLine().getErr())) {
            return CommandLine.ExitCode.USAGE;
        }
        AppContext context = AppContext.fromConfigFile(parent.parent().configFile());
        AccountDiscovery accountDiscovery = context.accountDiscovery();
        List<String> accounts =
                domain == null
                        ? accountDiscovery.discover(LdapObjectType.ACCOUNT)
                        : accountDiscovery.discoverForDomain(LdapObjectType.ACCOUNT, domain);

        PrintWriter out = spec.commandLine().getOut();
        for (String account : accounts) {
            out.println(account);
        }
        return 0;
    }
}
