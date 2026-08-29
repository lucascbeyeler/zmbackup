package io.zmbackup.core.domain;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of a {@link io.zmbackup.core.service.RestoreService} restore call, mirroring the
 * {@code TOTAL_COUNT}/{@code SUCCESS_COUNT}/{@code FAIL_COUNT} bookkeeping in the bash tool's
 * {@code restore_main_ldap}/{@code restore_main_mailbox}/{@code restore_main_domain}.
 *
 * @param total          how many accounts (or domains) were attempted
 * @param failedAccounts the accounts (or domains) that failed to restore
 */
public record RestoreResult(int total, List<String> failedAccounts) {

    public RestoreResult {
        Objects.requireNonNull(failedAccounts, "failedAccounts must not be null");
        failedAccounts = List.copyOf(failedAccounts);
        if (total < failedAccounts.size()) {
            throw new IllegalArgumentException("total must be at least the number of failed accounts");
        }
    }

    /** How many accounts (or domains) restored successfully. */
    public int succeededCount() {
        return total - failedAccounts.size();
    }

    /** Whether every attempted account (or domain) restored successfully. */
    public boolean allSucceeded() {
        return failedAccounts.isEmpty();
    }
}
