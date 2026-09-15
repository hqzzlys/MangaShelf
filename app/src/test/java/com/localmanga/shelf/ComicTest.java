package com.localmanga.shelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

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
}
