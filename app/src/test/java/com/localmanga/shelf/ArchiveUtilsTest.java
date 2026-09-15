package com.localmanga.shelf;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.text.Collator;
import java.util.Locale;

import org.junit.Test;

public final class ArchiveUtilsTest {
    private final Collator collator = Collator.getInstance(Locale.CHINA);

    @Test public void naturalOrderPlacesTwoBeforeTen() {
        assertTrue(compare("page2.jpg", "page10.jpg") < 0);
    }

    @Test public void naturalOrderHandlesNumbersLargerThanLong() {
        assertTrue(compare("page92233720368547758070.jpg", "page92233720368547758071.jpg") < 0);
    }

    @Test public void naturalOrderUsesFewerLeadingZerosFirst() {
        assertTrue(compare("page1.jpg", "page001.jpg") < 0);
    }

    @Test public void backupPathsRejectTraversalAndWindowsSeparators() {
        assertTrue(ArchiveUtils.isSafeRelativeBackupPath("chapter/001.jpg"));
        assertFalse(ArchiveUtils.isSafeRelativeBackupPath("../001.jpg"));
        assertFalse(ArchiveUtils.isSafeRelativeBackupPath("chapter\\001.jpg"));
        assertFalse(ArchiveUtils.isSafeRelativeBackupPath("/chapter/001.jpg"));
        assertFalse(ArchiveUtils.isSafeRelativeBackupPath("chapter/001\n.jpg"));
    }

    @Test public void backupIdsRejectDotSegmentsAndSeparators() {
        assertTrue(ArchiveUtils.isSafeBackupId("1723456789-abcd1234"));
        assertFalse(ArchiveUtils.isSafeBackupId(".."));
        assertFalse(ArchiveUtils.isSafeBackupId("folder/id"));
    }

    private int compare(String first, String second) {
        return ArchiveUtils.naturalCompare(first, second, collator);
    }
}
