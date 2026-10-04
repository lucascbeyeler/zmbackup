package io.zmbackup.core.domain;

public final class HumanReadableSize {

    private static final String[] UNITS = {"K", "M", "G", "T", "P"};

    private HumanReadableSize() {
    }

    public static String format(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        double value = bytes;
        int unitIndex = -1;
        while (value >= 1024 && unitIndex < UNITS.length - 1) {
            value /= 1024;
            unitIndex++;
        }
        long tenths = Math.round(value * 10);
        if (tenths >= 10240 && unitIndex < UNITS.length - 1) {
            tenths /= 1024;
            unitIndex++;
        }
        if (tenths % 10 == 0) {
            return (tenths / 10) + UNITS[unitIndex];
        }
        return (tenths / 10.0) + UNITS[unitIndex];
    }

    /**
     * Reverses {@link #format(long)}. Only meant for one-time migration of legacy values that were
     * stored pre-formatted (e.g. "1.2G") instead of as raw bytes - {@code format} rounds to one
     * decimal place, so the result is an approximation of the original byte count, not an exact
     * round trip.
     */
    public static long parseApprox(String formatted) {
        String trimmed = formatted.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("formatted must not be blank");
        }
        char unit = trimmed.charAt(trimmed.length() - 1);
        double value = Double.parseDouble(trimmed.substring(0, trimmed.length() - 1));
        int exponent = switch (unit) {
            case 'B' -> 0;
            case 'K' -> 1;
            case 'M' -> 2;
            case 'G' -> 3;
            case 'T' -> 4;
            case 'P' -> 5;
            default -> throw new IllegalArgumentException("Unrecognized size unit in '" + formatted + "'");
        };
        return Math.round(value * Math.pow(1024, exponent));
    }
}
