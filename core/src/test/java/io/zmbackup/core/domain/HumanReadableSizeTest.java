package io.zmbackup.core.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void parseApproxReadsPlainBytes() {
        assertEquals(512L, HumanReadableSize.parseApprox("512B"));
        assertEquals(0L, HumanReadableSize.parseApprox("0B"));
    }

    @Test
    void parseApproxReadsEachUnit() {
        assertEquals(1024L, HumanReadableSize.parseApprox("1K"));
        assertEquals(1024L * 1024, HumanReadableSize.parseApprox("1M"));
        assertEquals(1024L * 1024 * 1024, HumanReadableSize.parseApprox("1G"));
    }

    @Test
    void parseApproxHandlesFractionalValues() {
        assertEquals(Math.round(1.2 * 1024 * 1024 * 1024), HumanReadableSize.parseApprox("1.2G"));
    }

    @Test
    void parseApproxRejectsAnUnrecognizedUnit() {
        assertThrows(IllegalArgumentException.class, () -> HumanReadableSize.parseApprox("10X"));
    }

    @Test
    void parseApproxRejectsBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> HumanReadableSize.parseApprox("   "));
    }
}
