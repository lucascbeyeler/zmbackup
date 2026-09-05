package io.zmbackup.aws;

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
        if (tenths >= 10240 && unitIndex < UNITS.length - 1) {
            tenths /= 1024;
            unitIndex++;
        }
        if (tenths % 10 == 0) {
            return (tenths / 10) + UNITS[unitIndex];
        }
        return (tenths / 10.0) + UNITS[unitIndex];
    }
}
