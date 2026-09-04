package io.zmbackup.local;

/**
 * Formats byte counts the way {@link LocalStorageProvider} reports
 * {@link io.zmbackup.core.domain.BackupSession#size()} and
 * {@link io.zmbackup.core.domain.BackupAccountRecord#size()}: binary units ({@code K}, {@code M},
 * {@code G}, {@code T}, {@code P}), one decimal place when not a whole number, and a plain
 * {@code B} suffix below 1024 bytes (e.g. {@code "512B"}, {@code "1.5K"}, {@code "10M"}).
 */
final class HumanReadableSize {

    private static final String[] UNITS = {"K", "M", "G", "T", "P"};

    private HumanReadableSize() {
    }

    static String format(long bytes) {
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
        if (tenths % 10 == 0) {
            return (tenths / 10) + UNITS[unitIndex];
        }
        return (tenths / 10.0) + UNITS[unitIndex];
    }
}
