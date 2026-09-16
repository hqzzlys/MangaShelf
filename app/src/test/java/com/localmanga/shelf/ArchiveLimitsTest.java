package com.localmanga.shelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.IOException;

import org.junit.Test;

public final class ArchiveLimitsTest {
    @Test public void everyEntryCountsEvenWhenItIsNotAnImage() throws Exception {
        ArchiveLimits limits = new ArchiveLimits();
        limits.startEntry(10L);
        limits.recordBytes(10L, 10L, Long.MAX_VALUE, false);
        limits.startEntry(20L);
        limits.recordBytes(20L, 20L, Long.MAX_VALUE, true);

        assertEquals(2, limits.entries());
        assertEquals(30L, limits.totalBytes());
    }

    @Test public void rejectsOversizedSingleEntryBeforeExtraction() {
        ArchiveLimits limits = new ArchiveLimits();
        assertThrows(IOException.class, () -> limits.startEntry(ArchiveLimits.MAX_ENTRY_BYTES + 1L));
    }

    @Test public void rejectsExpansionBeyondTotalLimit() throws Exception {
        ArchiveLimits limits = new ArchiveLimits();
        for (int index = 0; index < 5; index++) {
            limits.startEntry(-1L);
            if (index < 4) limits.recordBytes(ArchiveLimits.MAX_ENTRY_BYTES, -1L, Long.MAX_VALUE, false);
        }
        assertThrows(IOException.class, () -> limits.recordBytes(1L, -1L, Long.MAX_VALUE, false));
    }

    @Test public void rejectsSuspiciousCompressionRatio() throws Exception {
        ArchiveLimits limits = new ArchiveLimits();
        limits.startEntry(-1L);
        assertThrows(IOException.class, () -> limits.recordBytes(201L, 1L, Long.MAX_VALUE, false));
    }

    @Test public void checksFreeSpaceOnlyForExtractedImages() throws Exception {
        ArchiveLimits limits = new ArchiveLimits();
        limits.startEntry(-1L);
        limits.recordBytes(1L, -1L, 1L, false);
        assertThrows(IOException.class, () -> limits.recordBytes(1L, -1L, 1L, true));
    }
}
