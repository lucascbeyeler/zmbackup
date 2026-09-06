package io.zmbackup.core.domain;

import java.util.regex.Pattern;

public final class Identifiers {

    public static final Pattern EMAIL = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    public static final Pattern DOMAIN = Pattern.compile("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");

    private Identifiers() {}
}
