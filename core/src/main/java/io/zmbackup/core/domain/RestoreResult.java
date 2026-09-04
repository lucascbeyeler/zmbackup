package io.zmbackup.core.domain;

import java.util.List;
import java.util.Objects;

public record RestoreResult(int total, List<String> failedAccounts) {

    public RestoreResult {
        Objects.requireNonNull(failedAccounts, "failedAccounts must not be null");
        failedAccounts = List.copyOf(failedAccounts);
        if (total < failedAccounts.size()) {
            throw new IllegalArgumentException("total must be at least the number of failed accounts");
        }
    }

    public int succeededCount() {
        return total - failedAccounts.size();
    }

    public boolean allSucceeded() {
        return failedAccounts.isEmpty();
    }
}
