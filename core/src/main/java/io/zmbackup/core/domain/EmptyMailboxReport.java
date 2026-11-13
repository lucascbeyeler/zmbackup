package io.zmbackup.core.domain;

import java.util.List;
import java.util.Objects;

public record EmptyMailboxReport(List<String> returnedNothing, List<String> noNewContent) {

    public static final EmptyMailboxReport NONE = new EmptyMailboxReport(List.of(), List.of());

    public EmptyMailboxReport {
        returnedNothing = List.copyOf(Objects.requireNonNull(returnedNothing, "returnedNothing must not be null"));
        noNewContent = List.copyOf(Objects.requireNonNull(noNewContent, "noNewContent must not be null"));
    }
}
