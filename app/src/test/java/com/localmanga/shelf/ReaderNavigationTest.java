package com.localmanga.shelf;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ReaderNavigationTest {
    @Test public void tapsNavigateAndCenterDoesNotTurnPage() {
        assertEquals(-1, ReaderNavigation.deltaForTap(0.1f, false));
        assertEquals(0, ReaderNavigation.deltaForTap(0.5f, false));
        assertEquals(1, ReaderNavigation.deltaForTap(0.9f, false));
        assertEquals(1, ReaderNavigation.deltaForTap(0.1f, true));
        assertEquals(-1, ReaderNavigation.deltaForTap(0.9f, true));
    }

    @Test public void swipesRespectReadingDirection() {
        assertEquals(1, ReaderNavigation.deltaForSwipe(-120f, false));
        assertEquals(-1, ReaderNavigation.deltaForSwipe(120f, false));
        assertEquals(-1, ReaderNavigation.deltaForSwipe(-120f, true));
        assertEquals(1, ReaderNavigation.deltaForSwipe(120f, true));
        assertEquals(0, ReaderNavigation.deltaForSwipe(0f, false));
    }

    @Test public void autoTurnAcceptsCustomSecondsWithinBounds() {
        assertEquals(1, ReaderNavigation.parseAutoTurnSeconds("1"));
        assertEquals(17, ReaderNavigation.parseAutoTurnSeconds(" 17 "));
        assertEquals(3600, ReaderNavigation.parseAutoTurnSeconds("3600"));
    }

    @Test public void autoTurnRejectsInvalidAndOverflowingInput() {
        for (String input : new String[]{"", " ", "0", "-1", "3601", "1.5", "abc", "999999999999999"}) {
            assertEquals(input, 0, ReaderNavigation.parseAutoTurnSeconds(input));
        }
    }

    @Test public void continuousReadingAdvancesLessThanOneScreen() {
        assertEquals(850, ReaderNavigation.scrollStep(1000));
        assertEquals(425, ReaderNavigation.scrollStep(500));
    }

    @Test public void continuousPositionRestoresWithinTheImageAfterResizing() {
        float fraction = ReaderNavigation.offsetFraction(1800, 6000);
        assertEquals(.3f, fraction, .0001f);
        assertEquals(900, ReaderNavigation.offsetPixels(fraction, 3000));
        assertEquals(0f, ReaderNavigation.offsetFraction(-100, 6000), 0f);
        assertEquals(0f, ReaderNavigation.offsetFraction(100, 0), 0f);
    }

    @Test public void requestedPageIsClampedToLibraryBounds() {
        assertEquals(0, ReaderNavigation.clampPage(-3, 10));
        assertEquals(4, ReaderNavigation.clampPage(4, 10));
        assertEquals(9, ReaderNavigation.clampPage(14, 10));
        assertEquals(0, ReaderNavigation.clampPage(4, 0));
    }
}
