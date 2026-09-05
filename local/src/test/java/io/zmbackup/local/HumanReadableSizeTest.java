package io.zmbackup.local;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HumanReadableSizeTest {

    @Test
    void formatsBytesBelow1024AsPlainBytes() {
        assertEquals("512B", HumanReadableSize.format(512));
    }

    @Test
    void formatsWithOneDecimalPlaceWhenNotWhole() {
        assertEquals("1.5K", HumanReadableSize.format(1536));
    }

    @Test
    void formatsWholeValuesWithoutADecimalPoint() {
        assertEquals("1K", HumanReadableSize.format(1024));
    }

    @Test
    void rollsOverToTheNextUnitAtA1024Boundary() {
        assertEquals("1G", HumanReadableSize.format(1024L * 1024 * 1024 - 1));
    }

    @Test
    void rollsOverToTheNextUnitWhenRoundingReaches1024() {
        assertEquals("1M", HumanReadableSize.format(1024L * 1024 - 1));
    }
}
