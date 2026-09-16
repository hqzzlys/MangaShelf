package com.localmanga.shelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class ComicTest {
    @Test public void emptyComicHasNoPagesOrCover() {
        Comic comic = new Comic("id", "title", new File("comic"), Collections.emptyList(),
                0, 0L, 0L, false, "");

        assertEquals(0, comic.pageCount());
        assertNull(comic.cover());
    }

    @Test public void coverUsesFirstPage() {
        File first = new File("001.jpg");
        File second = new File("002.jpg");
        Comic comic = new Comic("id", "title", new File("comic"), Arrays.asList(first, second),
                0, 0L, 0L, false, "");

        assertEquals(2, comic.pageCount());
        assertSame(first, comic.cover());
    }

    @Test public void unreadSinglePageComicIsNotComplete() {
        Comic comic = new Comic("id", "title", new File("comic"), Collections.singletonList(new File("001.jpg")),
                -1, 0L, 0L, false, "");

        assertTrue(comic.isUnread());
        assertFalse(comic.isComplete());
        assertEquals(0, comic.pagesRead());
    }

    @Test public void lastPageIsCompleteOnlyAfterReading() {
        Comic comic = new Comic("id", "title", new File("comic"), Arrays.asList(new File("001.jpg"), new File("002.jpg")),
                1, 0L, 123L, false, "");

        assertFalse(comic.isUnread());
        assertTrue(comic.isComplete());
        assertEquals(2, comic.pagesRead());
    }
}
